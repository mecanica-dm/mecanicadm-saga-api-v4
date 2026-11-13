package com.mecanicadm.saga_api.testutils;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    private static final String MONGO_IMAGE = "mongo:7.0";
    private static final List<String> COLLECTIONS = List.of("saga_instances", "outbox", "inbox", "locks");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(MONGO_IMAGE);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("saga_test"));
    }

    protected static void clearCollections(MongoTemplate mongoTemplate) {
        COLLECTIONS.forEach(collection -> mongoTemplate.getDb().getCollection(collection).deleteMany(new Document()));
    }
}
