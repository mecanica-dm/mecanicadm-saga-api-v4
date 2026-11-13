package com.mecanicadm.saga_api.core.saga.engine;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SagaDefinition {

    private final Map<Class<?>, List<TransitionRule<?>>> rulesByEvent = new HashMap<>();

    public SagaDefinition(List<SagaFlowStep> steps) {
        steps.forEach(step -> step.rules().forEach(rule ->
                rulesByEvent.computeIfAbsent(rule.eventType(), type -> new ArrayList<>()).add(rule)));
        ensureEveryEventIsHandled();
    }

    public Optional<TransitionRule<?>> ruleFor(SagaEvent event, SagaInstance saga) {
        return rulesByEvent.getOrDefault(event.getClass(), List.of()).stream()
                .filter(rule -> rule.matches(event, saga))
                .findFirst();
    }

    public Optional<TransitionRule<?>> terminalRuleFor(SagaEvent event, SagaInstance saga) {
        return rulesByEvent.getOrDefault(event.getClass(), List.of()).stream()
                .filter(TransitionRule::handlesTerminal)
                .filter(rule -> rule.matches(event, saga))
                .findFirst();
    }

    private void ensureEveryEventIsHandled() {
        List<String> missing = Arrays.stream(SagaEvent.class.getPermittedSubclasses())
                .filter(type -> !rulesByEvent.containsKey(type))
                .map(Class::getSimpleName)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Eventos sem regra de transicao: " + missing);
        }
    }
}
