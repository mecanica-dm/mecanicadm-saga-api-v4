package com.mecanicadm.saga_api.core.saga.event;

import java.util.UUID;

public record WorkOrderCommandRejectedEvent(UUID workOrderId, String eventType, String exception, String message) implements SagaEvent {
}
