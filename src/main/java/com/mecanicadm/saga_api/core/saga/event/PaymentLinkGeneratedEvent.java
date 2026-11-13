package com.mecanicadm.saga_api.core.saga.event;

import java.util.UUID;

public record PaymentLinkGeneratedEvent(UUID workOrderId, String paymentLink) implements SagaEvent {
}
