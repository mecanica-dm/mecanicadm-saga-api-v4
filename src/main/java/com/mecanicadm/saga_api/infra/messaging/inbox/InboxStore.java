package com.mecanicadm.saga_api.infra.messaging.inbox;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Component
public class InboxStore {

    private final MongoTemplate mongoTemplate;

    public InboxStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public int register(UUID messageId, String messageType, UUID workOrderId, String status, Instant processedAt) {
        InboxDocument existing = mongoTemplate.findById(messageId, InboxDocument.class);
        if (existing == null) {
            mongoTemplate.insert(new InboxDocument(messageId, messageType, workOrderId, InboxStatus.valueOf(status), null, processedAt));
            return 1;
        }
        if (!isReprocessable(existing)) {
            return 0;
        }
        update(messageId, new Update()
                .set("messageType", messageType)
                .set("workOrderId", workOrderId)
                .set("status", InboxStatus.valueOf(status))
                .unset("error")
                .set("processedAt", processedAt));
        return 1;
    }

    public int registerFailure(UUID messageId, String messageType, UUID workOrderId, String error, Instant processedAt) {
        InboxDocument existing = mongoTemplate.findById(messageId, InboxDocument.class);
        if (existing == null) {
            mongoTemplate.insert(new InboxDocument(messageId, messageType, workOrderId, InboxStatus.FAILED, error, processedAt));
            return 1;
        }
        if (!isReprocessable(existing)) {
            return 0;
        }
        update(messageId, new Update()
                .set("messageType", messageType)
                .set("workOrderId", workOrderId)
                .set("status", InboxStatus.FAILED)
                .set("error", error)
                .set("processedAt", processedAt));
        return 1;
    }

    public InboxDocument findById(UUID messageId) {
        return mongoTemplate.findById(messageId, InboxDocument.class);
    }

    private boolean isReprocessable(InboxDocument document) {
        return document.getStatus() == InboxStatus.FAILED || document.getStatus() == InboxStatus.REJECTED;
    }

    private void update(UUID messageId, Update update) {
        Query byId = query(where("_id").is(messageId));
        mongoTemplate.updateFirst(byId, update, InboxDocument.class);
    }
}
