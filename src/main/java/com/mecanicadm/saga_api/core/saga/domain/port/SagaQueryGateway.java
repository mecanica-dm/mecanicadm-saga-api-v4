package com.mecanicadm.saga_api.core.saga.domain.port;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.usecase.query.GetAllSagasQuery;
import com.mecanicadm.saga_api.core.shared.PageResult;

public interface SagaQueryGateway {

    PageResult<SagaInstance> findAll(GetAllSagasQuery query);
}
