package com.mecanicadm.saga_api.core.saga.command;

import com.mecanicadm.saga_api.core.saga.domain.ClientSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.LaborItemSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.MaterialItemSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.VehicleSnapshot;
import com.mecanicadm.saga_api.core.saga.domain.WorkOrderSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record BudgetWorkOrderSnapshot(
        BigDecimal totalPrice,
        String status,
        String observation,
        String description,
        Client client,
        Vehicle vehicle,
        List<MaterialItem> materialItems,
        List<LaborItem> laborItems) {

    static final String INITIAL_BUDGET_STATUS = "PENDING";

    public static BudgetWorkOrderSnapshot from(WorkOrderSnapshot workOrder) {
        return new BudgetWorkOrderSnapshot(
                workOrder.totalPrice(),
                INITIAL_BUDGET_STATUS,
                null,
                workOrder.description(),
                client(workOrder.client()),
                vehicle(workOrder.vehicle()),
                orEmpty(workOrder.materialItems()).stream().map(item -> material(item)).toList(),
                orEmpty(workOrder.laborItems()).stream().map(item -> labor(workOrder.workOrderId(), item)).toList());
    }

    private static Client client(ClientSnapshot client) {
        return client == null ? null
                : new Client(client.id(), client.name(), client.email(), client.document(), client.phone());
    }

    private static Vehicle vehicle(VehicleSnapshot vehicle) {
        return vehicle == null ? null
                : new Vehicle(vehicle.licensePlate(), vehicle.model(), vehicle.brand(), (short) vehicle.modelYear());
    }

    private static MaterialItem material(MaterialItemSnapshot item) {
        return new MaterialItem(item.id(), item.materialId(), item.quantity(), item.name(), item.brand(),
                item.description(), item.unitPrice(), item.type());
    }

    private static LaborItem labor(UUID workOrderId, LaborItemSnapshot item) {
        return new LaborItem(item.id(), workOrderId, item.laborId(), item.executionStartAt(), item.executionEndAt(),
                item.status(), item.name(), item.price());
    }

    private static <T> List<T> orEmpty(List<T> items) {
        return items == null ? List.of() : items;
    }

    public record Client(UUID id, String name, String email, String document, String phone) {
    }

    public record Vehicle(String licensePlate, String model, String brand, Short modelYear) {
    }

    public record MaterialItem(UUID id, UUID materialId, int quantity, String name, String brand, String description,
                               BigDecimal price, String type) {
    }

    public record LaborItem(UUID id, UUID workOrderId, UUID laborId, LocalDateTime executionStartAt,
                            LocalDateTime executionEndAt, String status, String name, BigDecimal price) {
    }
}
