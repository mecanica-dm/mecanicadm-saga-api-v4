package com.mecanicadm.saga_api.core.saga.flow.steps;

import com.mecanicadm.saga_api.core.saga.command.AuthorizeWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.ChangesRequestedWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetChangesRequestedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetExpiredEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.engine.TransitionRule;

import java.util.List;

import static com.mecanicadm.saga_api.core.saga.engine.Guards.awaitingCompensation;
import static com.mecanicadm.saga_api.core.saga.engine.Guards.inState;

public class CustomerDecisionStep implements SagaFlowStep {

    @Override
    public List<TransitionRule<?>> rules() {
        return List.of(
                TransitionRule.on(BudgetApprovedEvent.class, inState(SagaState.AWAITING_CUSTOMER_DECISION), this::authorizeWorkOrder),
                TransitionRule.on(BudgetRejectedEvent.class, inState(SagaState.AWAITING_CUSTOMER_DECISION), this::cancelWorkOrder),
                TransitionRule.on(BudgetExpiredEvent.class, inState(SagaState.AWAITING_CUSTOMER_DECISION), this::cancelWorkOrder),
                TransitionRule.on(BudgetCanceledEvent.class, awaitingCompensation(CancelBudgetIntegrationCommand.class), this::confirmBudgetCancellation),
                TransitionRule.on(BudgetCanceledEvent.class,
                        inState(SagaState.BUDGET_GENERATED, SagaState.AWAITING_CUSTOMER_DECISION), this::cancelWorkOrder),
                TransitionRule.on(BudgetChangesRequestedEvent.class, inState(SagaState.AWAITING_CUSTOMER_DECISION), this::reopenForChanges));
    }

    private List<SagaCommand> authorizeWorkOrder(SagaInstance saga, BudgetApprovedEvent event) {
        saga.moveTo(SagaState.AWAITING_EXECUTION);
        return List.of(new AuthorizeWorkOrderIntegrationCommand(saga.getWorkOrderId()));
    }

    private List<SagaCommand> cancelWorkOrder(SagaInstance saga, SagaEvent event) {
        CancelWorkOrderIntegrationCommand command = new CancelWorkOrderIntegrationCommand(saga.getWorkOrderId());
        saga.moveTo(SagaState.CANCELING);
        saga.expectCompensation(command);
        return List.of(command);
    }

    private List<SagaCommand> confirmBudgetCancellation(SagaInstance saga, BudgetCanceledEvent event) {
        saga.confirmCompensation(CancelBudgetIntegrationCommand.class);
        saga.settleCancellation();
        return List.of();
    }

    private List<SagaCommand> reopenForChanges(SagaInstance saga, BudgetChangesRequestedEvent event) {
        saga.moveTo(SagaState.CHANGES_REQUESTED);
        return List.of(new ChangesRequestedWorkOrderIntegrationCommand(saga.getWorkOrderId(), event.observation()));
    }
}
