package com.mecanicadm.saga_api.infra.messaging.consumer;

public enum ProcessingResult {
    PROCESSED,
    DUPLICATE,
    REJECTED
}
