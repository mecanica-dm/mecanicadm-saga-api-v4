package com.mecanicadm.saga_api.infra.messaging.outbox;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxSequence {

    private final AtomicLong last = new AtomicLong();

    public long next() {
        Instant now = Instant.now();
        long micros = now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
        return last.updateAndGet(previous -> Math.max(previous + 1, micros));
    }
}
