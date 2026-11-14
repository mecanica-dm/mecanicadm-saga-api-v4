package com.mecanicadm.saga_api.infra.features.saga.api.dto;

import com.mecanicadm.saga_api.core.shared.PageResult;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Página de resultados")
public record PageResponse<T>(
        @Schema(description = "Itens desta página") List<T> content,
        @Schema(description = "Número da página, a partir de 0") int page,
        @Schema(description = "Itens por página") int size,
        @Schema(description = "Total de itens em todas as páginas") long totalElements,
        @Schema(description = "Total de páginas") int totalPages) {

    public static <T> PageResponse<T> from(PageResult<T> result) {
        return new PageResponse<>(result.content(), result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}
