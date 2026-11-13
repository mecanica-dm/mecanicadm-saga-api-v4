package com.mecanicadm.saga_api.core.saga.flow.steps;

import com.mecanicadm.saga_api.core.saga.command.CancelWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCreatedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderUpdatedEvent;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.engine.TransitionRule;
import com.mecanicadm.saga_api.core.saga.engine.Compensation;
import com.mecanicadm.saga_api.core.saga.engine.CompensationPlanner;

import java.util.List;

import static com.mecanicadm.saga_api.core.saga.engine.Guards.always;
import static com.mecanicadm.saga_api.core.saga.engine.Guards.inStep;

public class RegistrationStep implements SagaFlowStep {

    private final CompensationPlanner compensationPlanner;

    public RegistrationStep(CompensationPlanner compensationPlanner) {
        this.compensationPlanner = compensationPlanner;
    }

    @Override
    public List<TransitionRule<?>> rules() {
        return List.of(
                TransitionRule.on(WorkOrderCreatedEvent.class, always(), (saga, event) -> List.of()),
                TransitionRule.on(WorkOrderUpdatedEvent.class, inStep(SagaStep.BUDGET, SagaStep.CUSTOMER_DECISION), this::reopenAndCompensate),
                TransitionRule.on(WorkOrderUpdatedEvent.class, always(), this::refreshWorkOrder));
    }

    @Override
    public List<Compensation> compensations() {
        return List.of(Compensation.of(SagaStep.REGISTRATION,
                saga -> !saga.isTerminal() && !saga.isIn(SagaState.CANCELING),
                saga -> new CancelWorkOrderIntegrationCommand(saga.getWorkOrderId())));
    }

    private List<SagaCommand> reopenAndCompensate(SagaInstance saga, WorkOrderUpdatedEvent event) {
        saga.replaceWorkOrder(event.snapshot());
        List<SagaCommand> commands = compensationPlanner.compensateFrom(SagaStep.BUDGET, saga, "WorkOrderUpdated");
        saga.discardBudget();
        saga.moveTo(SagaState.WORK_ORDER_OPEN);
        return commands;
    }

    private List<SagaCommand> refreshWorkOrder(SagaInstance saga, WorkOrderUpdatedEvent event) {
        saga.replaceWorkOrder(event.snapshot());
        return List.of();
    }
}
