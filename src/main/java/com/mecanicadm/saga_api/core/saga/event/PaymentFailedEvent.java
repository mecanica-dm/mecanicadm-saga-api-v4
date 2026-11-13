package com.mecanicadm.saga_api.core.saga.event;

import java.util.UUID;

public record PaymentFailedEvent(UUID workOrderId, String reason) implements SagaEvent {
}
