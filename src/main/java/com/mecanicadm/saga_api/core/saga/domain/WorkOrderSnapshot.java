package com.mecanicadm.saga_api.core.saga.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record WorkOrderSnapshot(
        UUID workOrderId,
        ClientSnapshot client,
        VehicleSnapshot vehicle,
        String description,
        String status,
        LocalDateTime executionStartAt,
        LocalDateTime executionEndAt,
        List<LaborItemSnapshot> laborItems,
        List<MaterialItemSnapshot> materialItems) {

    public BigDecimal totalPrice() {
        BigDecimal labor = laborItems == null ? BigDecimal.ZERO : laborItems.stream()
                .map(LaborItemSnapshot::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal material = materialItems == null ? BigDecimal.ZERO : materialItems.stream()
                .map(MaterialItemSnapshot::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return labor.add(material);
    }
}
