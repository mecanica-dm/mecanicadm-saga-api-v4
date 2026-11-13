package com.mecanicadm.saga_api.core.saga.event;

import java.math.BigDecimal;
import java.util.UUID;

public record BudgetUpdatedEvent(UUID workOrderId, BigDecimal totalPrice, String observation) implements SagaEvent {
}
