package com.mecanicadm.saga_api.core.saga.engine;

import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;

import java.util.function.Function;
import java.util.function.Predicate;

public record Compensation(SagaStep step, Predicate<SagaInstance> applies, Function<SagaInstance, SagaCommand> command) {

    public static Compensation of(SagaStep step, Predicate<SagaInstance> applies, Function<SagaInstance, SagaCommand> command) {
        return new Compensation(step, applies, command);
    }
}
