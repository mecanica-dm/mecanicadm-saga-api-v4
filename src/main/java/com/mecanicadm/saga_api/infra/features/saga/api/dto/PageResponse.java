package com.mecanicadm.saga_api.infra.features.saga.api.dto;

import com.mecanicadm.saga_api.core.shared.PageResult;

import java.util.List;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(PageResult<T> result) {
        return new PageResponse<>(result.content(), result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}
