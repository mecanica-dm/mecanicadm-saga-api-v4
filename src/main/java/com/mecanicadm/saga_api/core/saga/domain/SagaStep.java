package com.mecanicadm.saga_api.core.saga.domain;

public enum SagaStep {
    REGISTRATION,
    BUDGET,
    CUSTOMER_DECISION,
    EXECUTION,
    PAYMENT,
    DELIVERY;

    public boolean isAtLeast(SagaStep other) {
        return ordinal() >= other.ordinal();
    }
}
