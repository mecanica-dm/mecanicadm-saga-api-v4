package com.mecanicadm.saga_api.core.saga.engine;

import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CompensationPlanner {

    private static final Logger log = LoggerFactory.getLogger(CompensationPlanner.class);

    private final List<Compensation> compensations = new ArrayList<>();

    public void register(List<? extends SagaFlowStep> steps) {
        steps.forEach(step -> compensations.addAll(step.compensations()));
        compensations.sort(Comparator.comparing(Compensation::step).reversed());
    }

    public List<SagaCommand> compensateFrom(SagaStep firstStep, SagaInstance saga, String reason) {
        List<SagaCommand> commands = compensations.stream()
                .filter(compensation -> compensation.step().isAtLeast(firstStep))
                .filter(compensation -> compensation.applies().test(saga))
                .map(compensation -> compensation.command().apply(saga))
                .toList();
        commands.forEach(saga::expectCompensation);
        if (!commands.isEmpty()) {
            logCompensation(saga, reason, commands);
        }
        return commands;
    }

    public static void logCompensation(SagaInstance saga, String reason, List<SagaCommand> commands) {
        log.warn("SAGA_COMPENSATED workOrderId={} state={} reason=\"{}\" commands={}", saga.getWorkOrderId(), saga.getState(), reason,
                commands.stream().map(command -> command.getClass().getSimpleName()).toList());
    }
}
