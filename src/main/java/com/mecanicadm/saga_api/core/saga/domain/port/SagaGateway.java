package com.mecanicadm.saga_api.core.saga.domain.port;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;

import java.util.Optional;
import java.util.UUID;

public interface SagaGateway {
    Optional<SagaInstance> findById(UUID workOrderId);

    SagaInstance save(SagaInstance saga);
}
