package com.mecanicadm.saga_api.infra.features.saga.amqp;

import com.rabbitmq.client.Channel;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.core.saga.usecase.ProcessSagaEventUseCase;
import com.mecanicadm.saga_api.infra.messaging.consumer.MessageConsumer;
import com.mecanicadm.saga_api.infra.messaging.consumer.MessageConsumerSupport;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RabbitListener(
        queues = {"${messaging.queue.workorder-events:saga.workorder-events}", "${messaging.queue.finance-events:saga.finance-events}"},
        containerFactory = "eventListenerContainerFactory")
public class SagaEventsConsumer extends MessageConsumer {

    private final ProcessSagaEventUseCase processSagaEventUseCase;

    public SagaEventsConsumer(MessageConsumerSupport support, ProcessSagaEventUseCase processSagaEventUseCase) {
        super(support);
        this.processSagaEventUseCase = processSagaEventUseCase;
    }

    @RabbitHandler
    public void onEvent(@Payload SagaEvent event, Message message, Channel channel) throws IOException {
        consume(event.workOrderId(), event, message, channel, processSagaEventUseCase::execute);
    }
}
