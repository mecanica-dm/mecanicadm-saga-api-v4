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
import com.mecanicadm.saga_api.infra.features.saga.api.openapi.SagaOpenApi;
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
public class SagaController implements SagaOpenApi {

    private final GetAllSagasUseCase getAllSagasUseCase;
    private final GetSagaByIdUseCase getSagaByIdUseCase;

    public SagaController(GetAllSagasUseCase getAllSagasUseCase, GetSagaByIdUseCase getSagaByIdUseCase) {
        this.getAllSagasUseCase = getAllSagasUseCase;
        this.getSagaByIdUseCase = getSagaByIdUseCase;
    }

    @Override
    @GetMapping
    public ResponseEntity<PageResponse<SagaSummaryResponse>> getAll(
            @RequestParam(required = false) UUID workOrderId,
            @RequestParam(required = false) SagaState state,
            @RequestParam(required = false) SagaStep step,
            @RequestParam(required = false) SagaState pausedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String direction) {
        GetAllSagasQuery query = new GetAllSagasQuery(workOrderId, state, step, pausedFrom, createdFrom, createdTo,
                updatedFrom, updatedTo, page, size, sortBy, direction);
        return ResponseEntity.ok(PageResponse.from(getAllSagasUseCase.execute(query).map(SagaSummaryResponse::from)));
    }

    @Override
    @GetMapping("/{workOrderId}")
    public ResponseEntity<SagaResponse> getById(@PathVariable UUID workOrderId) {
        return ResponseEntity.ok(SagaResponse.from(getSagaByIdUseCase.execute(new GetSagaByIdQuery(workOrderId))));
    }
}
