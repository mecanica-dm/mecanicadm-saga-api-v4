package com.mecanicadm.saga_api.core.saga.engine;

import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

public record TransitionRule<E extends SagaEvent>(
        Class<E> eventType,
        Predicate<SagaInstance> guard,
        BiFunction<SagaInstance, E, List<SagaCommand>> action,
        boolean handlesTerminal) {

    public static <E extends SagaEvent> TransitionRule<E> on(Class<E> eventType, Predicate<SagaInstance> guard,
                                                              BiFunction<SagaInstance, E, List<SagaCommand>> action) {
        return new TransitionRule<>(eventType, guard, action, false);
    }

    public static <E extends SagaEvent> TransitionRule<E> afterTermination(Class<E> eventType, Predicate<SagaInstance> guard,
                                                                            BiFunction<SagaInstance, E, List<SagaCommand>> action) {
        return new TransitionRule<>(eventType, guard, action, true);
    }

    public boolean matches(SagaEvent event, SagaInstance saga) {
        return eventType.isInstance(event) && guard.test(saga);
    }

    public List<SagaCommand> apply(SagaInstance saga, SagaEvent event) {
        return action.apply(saga, eventType.cast(event));
    }
}
