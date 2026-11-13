package com.mecanicadm.saga_api.core.saga.usecase.query;

import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;

import java.time.Instant;
import java.util.UUID;

public record GetAllSagasQuery(
        UUID workOrderId,
        SagaState state,
        SagaStep step,
        SagaState pausedFrom,
        Instant createdFrom,
        Instant createdTo,
        Instant updatedFrom,
        Instant updatedTo,
        int page,
        int size,
        String sortBy,
        String direction) {

    public GetAllSagasQuery withPaging(int newPage, int newSize, String newSortBy, String newDirection) {
        return new GetAllSagasQuery(workOrderId, state, step, pausedFrom, createdFrom, createdTo, updatedFrom, updatedTo,
                newPage, newSize, newSortBy, newDirection);
    }
}
