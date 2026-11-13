package com.mecanicadm.saga_api.core.saga.event;

import java.util.UUID;

public record PaymentCanceledEvent(UUID workOrderId) implements SagaEvent {
}
