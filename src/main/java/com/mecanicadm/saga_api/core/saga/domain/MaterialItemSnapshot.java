package com.mecanicadm.saga_api.core.saga.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record MaterialItemSnapshot(
        UUID id,
        UUID materialId,
        String name,
        String brand,
        String description,
        String type,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal totalPrice) {
}
