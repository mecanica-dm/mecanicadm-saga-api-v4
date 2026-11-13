package com.mecanicadm.saga_api.infra.messaging.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
