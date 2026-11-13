package com.mecanicadm.saga_api.infra.messaging.consumer;

import com.mecanicadm.saga_api.infra.messaging.inbox.InboxStatus;
import com.mecanicadm.saga_api.shared.exception.DomainExceptionCore;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mecanicadm.saga_api.infra.messaging.outbox.RabbitOutboxMessageSender;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

public abstract class MessageConsumer {
    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);

    private final MessageConsumerSupport support;

    protected MessageConsumer(MessageConsumerSupport support) {
        this.support = support;
    }

    protected <T> void consume(UUID workOrderId, T message, Message amqpMessage, Channel channel, Consumer<T> handler) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        Envelope envelope = Envelope.of(workOrderId, amqpMessage.getMessageProperties());
        try {
            validate(envelope);
            ProcessingResult result = process(envelope, message, handler);
            log.info("Mensagem {} messageId={} workOrderId={} resultado={}",
                    envelope.eventType(), envelope.messageId(), envelope.workOrderId(), result);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Mensagem messageId={} tipo={} falhou; status=FAILED e enviada para a DLQ",
                    envelope.messageId(), envelope.eventType(), e);
            registerFailure(envelope, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    public <T> ProcessingResult process(Envelope envelope, T message, Consumer<T> handler) {
        try {
            return support.transactionTemplate().execute(status -> {
                if (!registerInInbox(envelope, InboxStatus.PROCESSED)) {
                    return ProcessingResult.DUPLICATE;
                }
                handler.accept(message);
                return ProcessingResult.PROCESSED;
            });
        } catch (DomainExceptionCore rejection) {
            return reject(envelope, rejection);
        }
    }

    private void validate(Envelope envelope) {
        if (envelope.messageId() == null || envelope.workOrderId() == null) {
            throw new InvalidMessageException("Mensagem sem messageId ou workOrderId");
        }
    }

    private void registerFailure(Envelope envelope, Exception cause) {
        if (envelope.messageId() == null || envelope.workOrderId() == null) {
            return;
        }
        try {
            support.transactionTemplate().executeWithoutResult(status -> support.inbox().registerFailure(
                    envelope.messageId(), envelope.eventType(), envelope.workOrderId(), cause.getMessage(), Instant.now()));
        } catch (RuntimeException e) {
            log.error("Não foi possível registrar a falha da mensagem {} no inbox", envelope.messageId(), e);
        }
    }

    private ProcessingResult reject(Envelope envelope, DomainExceptionCore rejection) {
        log.warn("Mensagem {} messageId={} workOrderId={} recusada: {}",
                envelope.eventType(), envelope.messageId(), envelope.workOrderId(), rejection.getMessageKey());

        return support.transactionTemplate().execute(status ->
                registerInInbox(envelope, InboxStatus.REJECTED) ? ProcessingResult.REJECTED : ProcessingResult.DUPLICATE);
    }

    private boolean registerInInbox(Envelope envelope, InboxStatus status) {
        return support.inbox().register(envelope.messageId(), envelope.eventType(), envelope.workOrderId(), status.name(), Instant.now()) == 1;
    }

    public record Envelope(UUID messageId, String eventType, UUID workOrderId) {

        static Envelope of(UUID workOrderId, MessageProperties properties) {
            Object eventType = properties.getHeaders().get(RabbitOutboxMessageSender.EVENT_TYPE_HEADER);
            Object messageIdHeader = properties.getHeaders().get(RabbitOutboxMessageSender.MESSAGE_ID_HEADER);
            String messageId = messageIdHeader != null ? messageIdHeader.toString() : properties.getMessageId();
            return new Envelope(parseId(messageId),
                    eventType != null ? eventType.toString() : null,
                    workOrderId);
        }

        private static UUID parseId(String value) {
            try {
                return value == null ? null : UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
