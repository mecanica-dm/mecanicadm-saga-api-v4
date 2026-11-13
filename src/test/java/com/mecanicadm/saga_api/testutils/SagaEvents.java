package com.mecanicadm.saga_api.testutils;

import com.mecanicadm.saga_api.core.saga.domain.ClientSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.LaborItemSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.MaterialItemSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.VehicleSnapshot;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCreatedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderUpdatedEvent;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class SagaEvents {

    private SagaEvents() {
    }

    private static final ClientSnapshot CLIENT = new ClientSnapshot(UUID.fromString("8b3f81e5-e118-4e31-8979-994cba5256e4"),
            "Deliveries Express", "express@deliver.com", "11222333000181", "48999999005");
    private static final VehicleSnapshot VEHICLE = new VehicleSnapshot("ABC-1234", "Volvo FH 460 - Caminhao", "Volvo", 2020);

    private static LaborItemSnapshot labor(String name, String price) {
        return new LaborItemSnapshot(UUID.randomUUID(), UUID.randomUUID(), name, new BigDecimal(price), "AWAITING_EXECUTION", null, null);
    }

    private static MaterialItemSnapshot material(String name, int quantity, String unitPrice) {
        BigDecimal unit = new BigDecimal(unitPrice);
        return new MaterialItemSnapshot(UUID.randomUUID(), UUID.randomUUID(), name, "Marca", "Descricao", "PART", unit, quantity,
                unit.multiply(BigDecimal.valueOf(quantity)));
    }

    public static WorkOrderCreatedEvent created(UUID workOrderId) {
        return new WorkOrderCreatedEvent(workOrderId, CLIENT, VEHICLE, "Revisao", "RECEIVED", null, null,
                List.of(labor("Limpeza de Carbono", "300.00"), labor("Alinhamento de Rodas", "200.00")),
                List.of(material("Filtro de Oleo", 2, "45.00"), material("Oleo 5W30", 2, "65.00")));
    }

    public static WorkOrderUpdatedEvent updated(UUID workOrderId) {
        return new WorkOrderUpdatedEvent(workOrderId, CLIENT, VEHICLE, "Revisao", "CHANGES_REQUESTED", null, null,
                List.of(labor("Limpeza de Carbono", "300.00")),
                List.of(material("Oleo 5W30", 2, "65.00")));
    }
}
