package com.mecanicadm.saga_api.infra.messaging.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document("outbox")
@CompoundIndex(name = "idx_outbox_status_sequence", def = "{'status': 1, 'sequence': 1}")
public class OutboxDocument {

    @Id
    private final UUID id;
    @Indexed(unique = true)
    private final long sequence;
    private final UUID aggregateId;
    private final String type;
    private final String payload;
    private final Instant occurredAt;
    private final OutboxStatus status;
    private final Instant publishedAt;
    private final String lastError;

    @SuppressWarnings("java:S107")
    public OutboxDocument(UUID id, long sequence, UUID aggregateId, String type, String payload, Instant occurredAt,
                          OutboxStatus status, Instant publishedAt, String lastError) {
        this.id = id;
        this.sequence = sequence;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.status = status;
        this.publishedAt = publishedAt;
        this.lastError = lastError;
    }

    public static OutboxDocument pending(UUID id, long sequence, UUID aggregateId, String type, String payload, Instant occurredAt) {
        return new OutboxDocument(id, sequence, aggregateId, type, payload, occurredAt, OutboxStatus.PENDING, null, null);
    }

    public UUID getId() {
        return id;
    }

    public long getSequence() {
        return sequence;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }
}
