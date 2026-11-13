package com.mecanicadm.saga_api.core.saga.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record LaborItemSnapshot(
        UUID id,
        UUID laborId,
        String name,
        BigDecimal price,
        String status,
        LocalDateTime executionStartAt,
        LocalDateTime executionEndAt) {
}
