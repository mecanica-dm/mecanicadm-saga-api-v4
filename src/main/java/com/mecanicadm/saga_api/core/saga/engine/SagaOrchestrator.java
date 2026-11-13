package com.mecanicadm.saga_api.core.saga.engine;

import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCreatedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final SagaDefinition definition;

    public SagaOrchestrator(SagaDefinition definition) {
        this.definition = definition;
    }

    public SagaInstance start(WorkOrderCreatedEvent event) {
        return SagaInstance.start(event);
    }

    public List<SagaCommand> handle(SagaInstance saga, SagaEvent event) {
        SagaState from = saga.getState();
        List<SagaCommand> commands = decide(saga, event);
        saga.recordTransition(event, from, commands);
        if (saga.getState() == SagaState.PAUSED && from != SagaState.PAUSED) {
            log.warn("SAGA_PAUSED workOrderId={} pausedFrom={} event={} reason=\"{}\"", saga.getWorkOrderId(), saga.getPausedFrom(),
                    event.getClass().getSimpleName(), saga.getPauseReason());
        }
        return commands;
    }

    private List<SagaCommand> decide(SagaInstance saga, SagaEvent event) {
        return (saga.isTerminal() ? definition.terminalRuleFor(event, saga) : definition.ruleFor(event, saga))
                .map(rule -> rule.apply(saga, event))
                .orElse(List.of());
    }
}
