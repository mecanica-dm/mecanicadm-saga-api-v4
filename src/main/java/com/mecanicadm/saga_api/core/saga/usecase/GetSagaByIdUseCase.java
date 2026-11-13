package com.mecanicadm.saga_api.core.saga.usecase;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaGateway;
import com.mecanicadm.saga_api.core.saga.exception.SagaExceptions;
import com.mecanicadm.saga_api.core.saga.usecase.query.GetSagaByIdQuery;
import com.mecanicadm.saga_api.shared.usecase.UseCase;

public class GetSagaByIdUseCase implements UseCase<GetSagaByIdQuery, SagaInstance> {

    private final SagaGateway gateway;

    public GetSagaByIdUseCase(SagaGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public SagaInstance execute(GetSagaByIdQuery query) {
        return gateway.findById(query.workOrderId()).orElseThrow(SagaExceptions.NotFound::new);
    }
}
