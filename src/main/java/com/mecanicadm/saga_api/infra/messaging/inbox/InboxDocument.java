package com.mecanicadm.saga_api.infra.messaging.inbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document("inbox")
public class InboxDocument {

    @Id
    private final UUID messageId;
    private final String messageType;
    private final UUID workOrderId;
    private final InboxStatus status;
    private final String error;
    @Indexed
    private final Instant processedAt;

    public InboxDocument(UUID messageId, String messageType, UUID workOrderId, InboxStatus status, String error, Instant processedAt) {
        this.messageId = messageId;
        this.messageType = messageType;
        this.workOrderId = workOrderId;
        this.status = status;
        this.error = error;
        this.processedAt = processedAt;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public String getMessageType() {
        return messageType;
    }

    public UUID getWorkOrderId() {
        return workOrderId;
    }

    public InboxStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
