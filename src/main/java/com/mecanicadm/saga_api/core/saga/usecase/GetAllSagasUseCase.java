package com.mecanicadm.saga_api.core.saga.usecase;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaQueryGateway;
import com.mecanicadm.saga_api.core.saga.usecase.query.GetAllSagasQuery;
import com.mecanicadm.saga_api.core.shared.PageResult;
import com.mecanicadm.saga_api.shared.usecase.UseCase;

import java.util.Set;

public class GetAllSagasUseCase implements UseCase<GetAllSagasQuery, PageResult<SagaInstance>> {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final String DEFAULT_SORT = "updatedAt";
    private static final Set<String> SORTABLE = Set.of("createdAt", DEFAULT_SORT, "state", "workOrderId");

    private final SagaQueryGateway gateway;

    public GetAllSagasUseCase(SagaQueryGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public PageResult<SagaInstance> execute(GetAllSagasQuery query) {
        int page = Math.max(query.page(), 0);
        int size = query.size() <= 0 ? DEFAULT_SIZE : Math.min(query.size(), MAX_SIZE);
        String sortBy = query.sortBy() != null && SORTABLE.contains(query.sortBy()) ? query.sortBy() : DEFAULT_SORT;
        String direction = "ASC".equalsIgnoreCase(query.direction()) ? "ASC" : "DESC";
        return gateway.findAll(query.withPaging(page, size, sortBy, direction));
    }
}
