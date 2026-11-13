package com.mecanicadm.saga_api.core.saga.event;

import java.math.BigDecimal;
import java.util.UUID;

public record BudgetGeneratedEvent(UUID workOrderId, BigDecimal totalPrice) implements SagaEvent {
}
