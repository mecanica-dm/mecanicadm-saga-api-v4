package com.mecanicadm.saga_api.core.saga.event;

import java.util.UUID;

public record PaymentLinkGenerationFailedEvent(UUID workOrderId) implements SagaEvent {
}
