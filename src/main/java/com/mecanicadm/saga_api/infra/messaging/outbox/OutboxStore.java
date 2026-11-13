package com.mecanicadm.saga_api.infra.messaging.outbox;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Component
public class OutboxStore {

    private final MongoTemplate mongoTemplate;

    public OutboxStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public OutboxDocument save(OutboxDocument document) {
        return mongoTemplate.insert(document);
    }

    public List<OutboxDocument> findPending(int limit) {
        Query pending = query(where("status").is(OutboxStatus.PENDING)).with(Sort.by("sequence")).limit(limit);
        return mongoTemplate.find(pending, OutboxDocument.class);
    }

    public List<OutboxDocument> findByAggregateId(UUID aggregateId) {
        return mongoTemplate.find(query(where("aggregateId").is(aggregateId)).with(Sort.by("sequence")), OutboxDocument.class);
    }

    public void markPublished(UUID id, Instant publishedAt) {
        mongoTemplate.updateFirst(query(where("_id").is(id)),
                new Update().set("status", OutboxStatus.PUBLISHED).set("publishedAt", publishedAt).unset("lastError"),
                OutboxDocument.class);
    }

    public void markFailed(UUID id, String error) {
        mongoTemplate.updateFirst(query(where("_id").is(id)),
                new Update().set("status", OutboxStatus.FAILED).set("lastError", error),
                OutboxDocument.class);
    }

    public long deletePublishedBefore(Instant threshold) {
        return mongoTemplate.remove(query(where("status").is(OutboxStatus.PUBLISHED).and("publishedAt").lt(threshold)),
                OutboxDocument.class).getDeletedCount();
    }
}
