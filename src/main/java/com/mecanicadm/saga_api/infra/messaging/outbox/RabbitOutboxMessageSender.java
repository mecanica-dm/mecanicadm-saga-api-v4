package com.mecanicadm.saga_api.infra.messaging.outbox;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class RabbitOutboxMessageSender {

    public static final String EVENT_TYPE_HEADER = "event-type";
    public static final String MESSAGE_ID_HEADER = "message-id";

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final OutboxProperties properties;

    public RabbitOutboxMessageSender(RabbitTemplate rabbitTemplate,
                                     @Value("${messaging.exchange.saga-output:saga-output}") String exchange,
                                     OutboxProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.properties = properties;
    }

    @SuppressWarnings("java:S2143")
    public void send(OutboxDocument outbox) {
        Message message = MessageBuilder
                .withBody(outbox.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setTimestamp(java.util.Date.from(outbox.getOccurredAt()))
                .setHeader(MESSAGE_ID_HEADER, outbox.getId().toString())
                .setHeader(EVENT_TYPE_HEADER, outbox.getType())
                .build();

        CorrelationData correlation = new CorrelationData("");
        rabbitTemplate.send(exchange, "", message, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(properties.confirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("Broker nack: " + confirm.getReason());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido aguardando o confirm do broker", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Sem confirm do broker: " + e.getMessage(), e);
        }
    }
}
