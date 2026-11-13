package com.mecanicadm.saga_api.core.saga.engine;

import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;

import java.util.function.Predicate;

public final class Guards {

    private Guards() {
    }

    public static Predicate<SagaInstance> always() {
        return saga -> true;
    }

    public static Predicate<SagaInstance> inState(SagaState... states) {
        return saga -> saga.isIn(states);
    }

    public static Predicate<SagaInstance> pausedFrom(SagaState... states) {
        return saga -> saga.isPausedFrom(states);
    }

    public static Predicate<SagaInstance> inStep(SagaStep... steps) {
        return saga -> saga.isInStep(steps);
    }

    public static Predicate<SagaInstance> awaitingCompensation(Class<? extends SagaCommand> command) {
        return saga -> saga.isAwaitingCompensation(command);
    }
}
