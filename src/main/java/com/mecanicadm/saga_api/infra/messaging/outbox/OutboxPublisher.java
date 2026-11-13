package com.mecanicadm.saga_api.infra.messaging.outbox;

import com.mecanicadm.saga_api.infra.messaging.lock.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String LOCK_NAME = "outbox-publisher";
    private static final long LOCK_MARGIN_MS = 10_000;

    private final OutboxStore store;
    private final DistributedLock lock;
    private final RabbitOutboxMessageSender sender;
    private final OutboxProperties properties;

    public OutboxPublisher(OutboxStore store, DistributedLock lock, RabbitOutboxMessageSender sender, OutboxProperties properties) {
        this.store = store;
        this.lock = lock;
        this.sender = sender;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${messaging.outbox.publisher.interval-ms:1000}")
    public void publishPending() {
        long leaseMs = properties.batchSize() * properties.confirmTimeoutMs() + LOCK_MARGIN_MS;
        if (!lock.tryAcquire(LOCK_NAME, leaseMs)) {
            return;
        }
        try {
            for (OutboxDocument message : store.findPending(properties.batchSize())) {
                publish(message);
            }
        } finally {
            lock.release(LOCK_NAME);
        }
    }

    @Scheduled(cron = "${messaging.outbox.cleanup.cron:0 0 3 * * *}")
    public long cleanPublished() {
        long deleted = store.deletePublishedBefore(Instant.now().minus(Duration.ofDays(properties.retentionDays())));
        if (deleted > 0) {
            log.info("Outbox: {} mensagens publicadas removidas", deleted);
        }
        return deleted;
    }

    private void publish(OutboxDocument message) {
        try {
            sender.send(message);
            store.markPublished(message.getId(), Instant.now());
        } catch (RuntimeException e) {
            store.markFailed(message.getId(), e.getMessage());
            log.error("Falha ao publicar outbox id={} type={} workOrderId={}; status=FAILED",
                    message.getId(), message.getType(), message.getAggregateId(), e);
        }
    }
}
