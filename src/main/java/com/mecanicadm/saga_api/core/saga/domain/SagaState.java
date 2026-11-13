package com.mecanicadm.saga_api.core.saga.domain;

public enum SagaState {
    WORK_ORDER_OPEN(SagaStep.REGISTRATION),
    AWAITING_BUDGET(SagaStep.BUDGET),
    BUDGET_GENERATED(SagaStep.BUDGET),
    AWAITING_CUSTOMER_DECISION(SagaStep.CUSTOMER_DECISION),
    CHANGES_REQUESTED(SagaStep.CUSTOMER_DECISION),
    AWAITING_EXECUTION(SagaStep.EXECUTION),
    IN_EXECUTION(SagaStep.EXECUTION),
    AWAITING_PAYMENT_LINK(SagaStep.PAYMENT),
    AWAITING_PAYMENT(SagaStep.PAYMENT),
    AWAITING_DELIVERY(SagaStep.DELIVERY),
    CANCELING(null),
    PAUSED(null),
    COMPLETED(null),
    CANCELED(null);

    private final SagaStep step;

    SagaState(SagaStep step) {
        this.step = step;
    }

    public SagaStep step() {
        return step;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELED;
    }
}
