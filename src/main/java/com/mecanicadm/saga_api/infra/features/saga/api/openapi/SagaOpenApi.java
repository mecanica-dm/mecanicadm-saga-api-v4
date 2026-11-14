package com.mecanicadm.saga_api.infra.features.saga.api.openapi;

import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.infra.features.saga.api.dto.PageResponse;
import com.mecanicadm.saga_api.infra.features.saga.api.dto.SagaResponse;
import com.mecanicadm.saga_api.infra.features.saga.api.dto.SagaSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Sagas", description = "Acompanhamento do andamento das ordens de serviço no fluxo distribuído (há uma saga por OS)")
public interface SagaOpenApi {

    @Operation(summary = "Listar as sagas",
            description = "Mostra o andamento das ordens de serviço no fluxo entre os microsserviços. Todos os filtros são opcionais "
                    + "e podem ser combinados. É útil, por exemplo, para achar as sagas pausadas que esperam por alguém "
                    + "(`state=PAUSED`) ou as que estão na etapa de pagamento (`step=PAYMENT`). Sem ordenação informada, "
                    + "as sagas atualizadas mais recentemente vêm primeiro.")
    @ApiResponse(responseCode = "200", description = "Página de sagas")
    @ApiResponse(responseCode = "400", description = "Filtro inválido (por exemplo, um estado que não existe)", content = @Content)
    @ApiResponse(responseCode = "401", description = "Token ausente ou inválido", content = @Content)
    ResponseEntity<PageResponse<SagaSummaryResponse>> getAll(
            @Parameter(description = "Id da ordem de serviço (a saga tem o mesmo id da OS)") UUID workOrderId,
            @Parameter(description = "Situação atual da saga") SagaState state,
            @Parameter(description = "Etapa do fluxo. Uma saga pausada conta na etapa em que parou") SagaStep step,
            @Parameter(description = "Estado em que a saga estava quando foi pausada") SagaState pausedFrom,
            @Parameter(description = "Criadas a partir desta data e hora (ISO-8601, por exemplo 2026-11-01T00:00:00Z)") Instant createdFrom,
            @Parameter(description = "Criadas até esta data e hora (ISO-8601)") Instant createdTo,
            @Parameter(description = "Atualizadas a partir desta data e hora (ISO-8601)") Instant updatedFrom,
            @Parameter(description = "Atualizadas até esta data e hora (ISO-8601)") Instant updatedTo,
            @Parameter(description = "Número da página, a partir de 0", example = "0") int page,
            @Parameter(description = "Quantidade de itens por página, de 1 a 100", example = "20") int size,
            @Parameter(description = "Campo de ordenação: createdAt, updatedAt (padrão), state ou workOrderId") String sortBy,
            @Parameter(description = "Sentido da ordenação: ASC ou DESC (padrão)") String direction);

    @Operation(summary = "Consultar uma saga e o seu histórico",
            description = "A saga tem o mesmo id da ordem de serviço. Além do estado atual, o histórico mostra, evento por evento, "
                    + "como ela chegou até aqui: o que cada serviço informou, para qual estado a saga foi e quais commands ela enviou.")
    @ApiResponse(responseCode = "200", description = "Saga com o histórico completo")
    @ApiResponse(responseCode = "401", description = "Token ausente ou inválido", content = @Content)
    @ApiResponse(responseCode = "404", description = "Saga não encontrada para esta ordem de serviço", content = @Content)
    ResponseEntity<SagaResponse> getById(@Parameter(description = "Id da ordem de serviço") UUID workOrderId);
}
