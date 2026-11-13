package com.mecanicadm.saga_api.core.saga.event;

import java.util.UUID;

public record WorkOrderExecutionStartedEvent(UUID workOrderId) implements SagaEvent {
}
