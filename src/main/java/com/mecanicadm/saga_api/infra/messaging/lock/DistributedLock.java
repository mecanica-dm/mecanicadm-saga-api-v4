package com.mecanicadm.saga_api.infra.messaging.lock;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Component
public class DistributedLock {

    private static final String COLLECTION = "locks";

    private final MongoTemplate mongoTemplate;
    private final String owner = UUID.randomUUID().toString();

    public DistributedLock(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public boolean tryAcquire(String name, long leaseMs) {
        Instant now = Instant.now();
        try {
            Document acquired = mongoTemplate.findAndModify(
                    query(where("_id").is(name).and("lockedUntil").lt(now)),
                    new Update().set("lockedUntil", now.plusMillis(leaseMs)).set("owner", owner),
                    FindAndModifyOptions.options().upsert(true).returnNew(true),
                    Document.class, COLLECTION);
            return acquired != null;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void release(String name) {
        mongoTemplate.updateFirst(query(where("_id").is(name).and("owner").is(owner)),
                new Update().set("lockedUntil", Instant.EPOCH), Document.class, COLLECTION);
    }
}
