package com.mecanicadm.saga_api.infra.messaging.lock;

import com.mecanicadm.saga_api.testutils.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@SpringBootTest
class DistributedLockIT extends AbstractIntegrationTest {

    @Autowired
    private DistributedLock lock;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanUp() {
        clearCollections(mongoTemplate);
    }

    @Test
    @DisplayName("Deve conceder o lock uma vez e negar enquanto o lease estiver valido")
    void shouldGrantLockOnceWhileLeaseIsValid() {
        assertTrue(lock.tryAcquire("teste", 60_000));
        assertFalse(lock.tryAcquire("teste", 60_000));
    }

    @Test
    @DisplayName("Deve conceder o lock de novo depois de liberado")
    void shouldGrantLockAgainAfterRelease() {
        lock.tryAcquire("teste", 60_000);

        lock.release("teste");

        assertTrue(lock.tryAcquire("teste", 60_000));
    }

    @Test
    @DisplayName("Deve conceder o lock de novo quando o lease expirar")
    void shouldGrantLockAgainWhenLeaseExpires() {
        lock.tryAcquire("teste", 60_000);
        mongoTemplate.updateFirst(query(where("_id").is("teste")),
                new Update().set("lockedUntil", Instant.now().minusSeconds(1)), "locks");

        assertTrue(lock.tryAcquire("teste", 60_000));
    }

    @Test
    @DisplayName("Deve manter locks de nomes diferentes independentes")
    void shouldKeepDifferentLocksIndependent() {
        assertTrue(lock.tryAcquire("a", 60_000));
        assertTrue(lock.tryAcquire("b", 60_000));
    }
}
