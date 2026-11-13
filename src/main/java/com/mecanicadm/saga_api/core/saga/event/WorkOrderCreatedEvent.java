package com.mecanicadm.saga_api.core.saga.event;

import com.mecanicadm.saga_api.core.saga.domain.ClientSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.LaborItemSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.MaterialItemSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.VehicleSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.WorkOrderSnapshot;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record WorkOrderCreatedEvent(
        UUID workOrderId,
        ClientSnapshot client,
        VehicleSnapshot vehicle,
        String description,
        String status,
        LocalDateTime executionStartAt,
        LocalDateTime executionEndAt,
        List<LaborItemSnapshot> laborItems,
        List<MaterialItemSnapshot> materialItems) implements SagaEvent {

    public WorkOrderSnapshot snapshot() {
        return new WorkOrderSnapshot(workOrderId, client, vehicle, description, status, executionStartAt, executionEndAt,
                laborItems, materialItems);
    }
}
