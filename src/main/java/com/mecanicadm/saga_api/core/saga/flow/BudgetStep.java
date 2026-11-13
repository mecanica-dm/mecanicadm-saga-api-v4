package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.GenerateBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.core.saga.event.BudgetGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionFailedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetUpdatedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderDiagnosedEvent;
import com.mecanicadm.saga_api.core.saga.engine.Compensation;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.engine.TransitionRule;

import java.util.List;

import static com.mecanicadm.saga_api.core.saga.engine.Guards.inState;
import static com.mecanicadm.saga_api.core.saga.engine.Guards.pausedFrom;

public class BudgetStep implements SagaFlowStep {

    @Override
    public List<TransitionRule<?>> rules() {
        return List.of(
                TransitionRule.on(WorkOrderDiagnosedEvent.class, inState(SagaState.WORK_ORDER_OPEN), this::requestBudget),
                TransitionRule.on(BudgetGeneratedEvent.class, inState(SagaState.AWAITING_BUDGET), this::registerGeneratedBudget),
                TransitionRule.on(BudgetUpdatedEvent.class,
                        inState(SagaState.BUDGET_GENERATED, SagaState.AWAITING_CUSTOMER_DECISION), this::registerUpdatedBudget),
                TransitionRule.on(BudgetSentForDecisionEvent.class, inState(SagaState.BUDGET_GENERATED), this::awaitCustomer),
                TransitionRule.on(BudgetSentForDecisionEvent.class, pausedFrom(SagaState.BUDGET_GENERATED), this::awaitCustomer),
                TransitionRule.on(BudgetSentForDecisionFailedEvent.class, inState(SagaState.BUDGET_GENERATED), this::pauseSending));
    }

    @Override
    public List<Compensation> compensations() {
        return List.of(Compensation.of(SagaStep.BUDGET, SagaInstance::hasActiveBudget,
                saga -> new CancelBudgetIntegrationCommand(saga.getWorkOrderId())));
    }

    private List<SagaCommand> requestBudget(SagaInstance saga, WorkOrderDiagnosedEvent event) {
        saga.moveTo(SagaState.AWAITING_BUDGET);
        return List.of(GenerateBudgetIntegrationCommand.from(saga.getWorkOrderId(), saga.getWorkOrder()));
    }

    private List<SagaCommand> registerGeneratedBudget(SagaInstance saga, BudgetGeneratedEvent event) {
        saga.registerBudget(event.totalPrice());
        saga.moveTo(SagaState.BUDGET_GENERATED);
        return List.of();
    }

    private List<SagaCommand> registerUpdatedBudget(SagaInstance saga, BudgetUpdatedEvent event) {
        saga.registerBudget(event.totalPrice());
        return List.of();
    }

    private List<SagaCommand> awaitCustomer(SagaInstance saga, BudgetSentForDecisionEvent event) {
        saga.moveTo(SagaState.AWAITING_CUSTOMER_DECISION);
        return List.of();
    }

    private List<SagaCommand> pauseSending(SagaInstance saga, BudgetSentForDecisionFailedEvent event) {
        saga.pause("BudgetSentForDecisionFailed");
        return List.of();
    }
}
