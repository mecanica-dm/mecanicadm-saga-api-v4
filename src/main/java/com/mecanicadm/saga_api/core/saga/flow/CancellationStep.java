package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.command.CancelWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCanceledEvent;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.engine.TransitionRule;
import com.mecanicadm.saga_api.core.saga.engine.CompensationPlanner;

import java.util.List;

import static com.mecanicadm.saga_api.core.saga.engine.Guards.always;
import static com.mecanicadm.saga_api.core.saga.engine.Guards.inState;
import static com.mecanicadm.saga_api.core.saga.engine.Guards.pausedFrom;

public class CancellationStep implements SagaFlowStep {

    private final CompensationPlanner compensationPlanner;

    public CancellationStep(CompensationPlanner compensationPlanner) {
        this.compensationPlanner = compensationPlanner;
    }

    @Override
    public List<TransitionRule<?>> rules() {
        return List.of(
                TransitionRule.on(WorkOrderCanceledEvent.class, inState(SagaState.CANCELING).or(pausedFrom(SagaState.CANCELING)), this::confirmCancellation),
                TransitionRule.on(WorkOrderCanceledEvent.class, always(), this::compensateAndCancel));
    }

    private List<SagaCommand> confirmCancellation(SagaInstance saga, WorkOrderCanceledEvent event) {
        saga.confirmCompensation(CancelWorkOrderIntegrationCommand.class);
        saga.settleCancellation();
        return List.of();
    }

    private List<SagaCommand> compensateAndCancel(SagaInstance saga, WorkOrderCanceledEvent event) {
        List<SagaCommand> commands = compensationPlanner.compensateFrom(SagaStep.BUDGET, saga, "WorkOrderCanceled");
        saga.moveTo(SagaState.CANCELING);
        saga.settleCancellation();
        return commands;
    }
}
