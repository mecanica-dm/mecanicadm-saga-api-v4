package com.mecanicadm.saga_api.core.saga.event;

import java.util.UUID;

public record WorkOrderVehicleDeliveredEvent(UUID workOrderId) implements SagaEvent {
}
