package com.mecanicadm.saga_api.core.saga.event;

import java.util.UUID;

public record BudgetSentForDecisionEvent(UUID workOrderId) implements SagaEvent {
}
