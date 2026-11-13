package com.mecanicadm.saga_api.infra.messaging.lock;

import com.mecanicadm.saga_api.testutils.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void shouldGrantLockAgainWhenLeaseExpires() throws InterruptedException {
        lock.tryAcquire("teste", 50);

        Thread.sleep(120);

        assertTrue(lock.tryAcquire("teste", 60_000));
    }

    @Test
    @DisplayName("Deve manter locks de nomes diferentes independentes")
    void shouldKeepDifferentLocksIndependent() {
        assertTrue(lock.tryAcquire("a", 60_000));
        assertTrue(lock.tryAcquire("b", 60_000));
    }
}
