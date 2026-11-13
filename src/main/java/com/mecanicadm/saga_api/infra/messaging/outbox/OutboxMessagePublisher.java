package com.mecanicadm.saga_api.infra.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mecanicadm.saga_api.core.shared.messaging.MessagePublisher;
import com.mecanicadm.saga_api.infra.messaging.serialization.MessageJsonSerializer;
import com.mecanicadm.saga_api.shared.exception.TechnicalException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class OutboxMessagePublisher implements MessagePublisher {
    private final OutboxStore store;
    private final OutboxSequence sequence;
    private final MessageJsonSerializer serializer;

    public OutboxMessagePublisher(OutboxStore store, OutboxSequence sequence, MessageJsonSerializer serializer) {
        this.store = store;
        this.sequence = sequence;
        this.serializer = serializer;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID workOrderId, Object message) {
        String eventType = message.getClass().getSimpleName();
        store.save(OutboxDocument.pending(
                UUID.randomUUID(),
                sequence.next(),
                workOrderId,
                eventType,
                serialize(message, eventType),
                Instant.now()));
    }

    private String serialize(Object message, String eventType) {
        try {
            return serializer.toJson(message);
        } catch (JsonProcessingException e) {
            throw new TechnicalException("error.technical.outbox.serialization", eventType);
        }
    }
}
