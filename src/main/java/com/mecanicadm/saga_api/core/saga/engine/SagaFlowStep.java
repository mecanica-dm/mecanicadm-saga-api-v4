package com.mecanicadm.saga_api.core.saga.engine;

import java.util.List;

public interface SagaFlowStep {

    List<TransitionRule<?>> rules();

    default List<Compensation> compensations() {
        return List.of();
    }
}
