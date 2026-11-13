package com.mecanicadm.saga_api.infra.features.saga.api;

import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.core.saga.usecase.GetAllSagasUseCase;
import com.mecanicadm.saga_api.core.saga.usecase.GetSagaByIdUseCase;
import com.mecanicadm.saga_api.core.saga.usecase.query.GetAllSagasQuery;
import com.mecanicadm.saga_api.core.saga.usecase.query.GetSagaByIdQuery;
import com.mecanicadm.saga_api.infra.features.saga.api.dto.PageResponse;
import com.mecanicadm.saga_api.infra.features.saga.api.dto.SagaResponse;
import com.mecanicadm.saga_api.infra.features.saga.api.dto.SagaSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/sagas")
@Tag(name = "Sagas", description = "Consulta do andamento dos fluxos distribuidos (uma saga por OS)")
public class SagaController {

    private final GetAllSagasUseCase getAllSagasUseCase;
    private final GetSagaByIdUseCase getSagaByIdUseCase;

    public SagaController(GetAllSagasUseCase getAllSagasUseCase, GetSagaByIdUseCase getSagaByIdUseCase) {
        this.getAllSagasUseCase = getAllSagasUseCase;
        this.getSagaByIdUseCase = getSagaByIdUseCase;
    }

    @GetMapping
    @Operation(summary = "Lista as sagas",
            description = "Todos os filtros sao opcionais e combinados com E. O filtro `step` considera uma saga pausada na etapa em que ela parou. "
                    + "Ordenacao por createdAt, updatedAt, state ou workOrderId (padrao: updatedAt decrescente). Tamanho de pagina maximo: 100.")
    public ResponseEntity<PageResponse<SagaSummaryResponse>> getAll(
            @Parameter(description = "Id da OS (a saga tem o mesmo id)") @RequestParam(required = false) UUID workOrderId,
            @Parameter(description = "Situacao atual da saga") @RequestParam(required = false) SagaState state,
            @Parameter(description = "Etapa do fluxo") @RequestParam(required = false) SagaStep step,
            @Parameter(description = "Estado de onde a saga foi pausada") @RequestParam(required = false) SagaState pausedFrom,
            @Parameter(description = "Criada a partir de (ISO-8601, ex.: 2026-11-01T00:00:00Z)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @Parameter(description = "Criada ate (ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @Parameter(description = "Atualizada a partir de (ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedFrom,
            @Parameter(description = "Atualizada ate (ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedTo,
            @Parameter(description = "Pagina, a partir de 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Itens por pagina (1 a 100)") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "createdAt, updatedAt, state ou workOrderId") @RequestParam(required = false) String sortBy,
            @Parameter(description = "ASC ou DESC") @RequestParam(required = false) String direction) {
        GetAllSagasQuery query = new GetAllSagasQuery(workOrderId, state, step, pausedFrom, createdFrom, createdTo,
                updatedFrom, updatedTo, page, size, sortBy, direction);
        return ResponseEntity.ok(PageResponse.from(getAllSagasUseCase.execute(query).map(SagaSummaryResponse::from)));
    }

    @GetMapping("/{workOrderId}")
    @Operation(summary = "Consulta uma saga com o historico completo",
            description = "A saga e identificada pelo id da OS. O historico mostra, evento a evento, como a saga chegou ao estado atual.")
    public ResponseEntity<SagaResponse> getById(@PathVariable UUID workOrderId) {
        return ResponseEntity.ok(SagaResponse.from(getSagaByIdUseCase.execute(new GetSagaByIdQuery(workOrderId))));
    }
}
