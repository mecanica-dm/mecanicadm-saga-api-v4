package com.mecanicadm.saga_api.core.saga.flow.steps;

import com.mecanicadm.saga_api.core.saga.command.AuthorizeWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.ChangesRequestedWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.GenerateBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.GeneratePaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.RefundPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.ReleaseWorkOrderVehicleIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.core.saga.event.FinanceCommandRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCommandRejectedEvent;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.engine.TransitionRule;
import com.mecanicadm.saga_api.core.saga.engine.CompensationPlanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.mecanicadm.saga_api.core.saga.engine.Guards.always;

public class CommandRejectionStep implements SagaFlowStep {

    enum Reaction {
        COMPENSATE_AND_CANCEL,
        PAUSE
    }

    private record Expectation(String command, Reaction reaction) {

        static Expectation of(Class<? extends SagaCommand> command, Reaction reaction) {
            return new Expectation(command.getSimpleName(), reaction);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CommandRejectionStep.class);

    private static final String REFUND_COMMAND = RefundPaymentIntegrationCommand.class.getSimpleName();

    private static final Map<SagaState, Expectation> AWAITED_COMMAND = Map.of(
            SagaState.AWAITING_BUDGET, Expectation.of(GenerateBudgetIntegrationCommand.class, Reaction.COMPENSATE_AND_CANCEL),
            SagaState.CANCELING, Expectation.of(CancelWorkOrderIntegrationCommand.class, Reaction.PAUSE),
            SagaState.AWAITING_EXECUTION, Expectation.of(AuthorizeWorkOrderIntegrationCommand.class, Reaction.COMPENSATE_AND_CANCEL),
            SagaState.CHANGES_REQUESTED, Expectation.of(ChangesRequestedWorkOrderIntegrationCommand.class, Reaction.COMPENSATE_AND_CANCEL),
            SagaState.AWAITING_PAYMENT_LINK, Expectation.of(GeneratePaymentIntegrationCommand.class, Reaction.COMPENSATE_AND_CANCEL),
            SagaState.AWAITING_DELIVERY, Expectation.of(ReleaseWorkOrderVehicleIntegrationCommand.class, Reaction.COMPENSATE_AND_CANCEL));

    private final CompensationPlanner compensationPlanner;

    public CommandRejectionStep(CompensationPlanner compensationPlanner) {
        this.compensationPlanner = compensationPlanner;
    }

    @Override
    public List<TransitionRule<?>> rules() {
        return List.of(
                TransitionRule.on(WorkOrderCommandRejectedEvent.class, always(),
                        (saga, event) -> react(saga, "WorkOrderCommandRejected", event.eventType(), event.exception(), event.message())),
                TransitionRule.on(FinanceCommandRejectedEvent.class, always(),
                        (saga, event) -> react(saga, "FinanceCommandRejected", event.eventType(), event.exception(), event.message())),
                TransitionRule.afterTermination(WorkOrderCommandRejectedEvent.class, always(),
                        (saga, event) -> logCompensationFailure(saga, event.eventType(), event.exception(), event.message())),
                TransitionRule.afterTermination(FinanceCommandRejectedEvent.class, always(),
                        (saga, event) -> logCompensationFailure(saga, event.eventType(), event.exception(), event.message())));
    }

    private List<SagaCommand> logCompensationFailure(SagaInstance saga, String rejectedCommand, String exception, String message) {
        if (REFUND_COMMAND.equals(rejectedCommand)) {
            log.error("SAGA_COMPENSATION_FAILED workOrderId={} state={} command={} reason=\"{}: {}\" action=\"estorno manual necessario\"",
                    saga.getWorkOrderId(), saga.getState(), rejectedCommand, exception, message);
        }
        return List.of();
    }

    private List<SagaCommand> react(SagaInstance saga, String source, String rejectedCommand, String exception, String message) {
        logCompensationFailure(saga, rejectedCommand, exception, message);
        String reason = "%s: %s - %s: %s".formatted(source, rejectedCommand, exception, message);
        if (isNothingToUndo(saga, rejectedCommand)) {
            return List.of();
        }
        if (REFUND_COMMAND.equals(rejectedCommand) && saga.isAwaitingCompensation(RefundPaymentIntegrationCommand.class)
                && !saga.isIn(SagaState.PAUSED)) {
            saga.pause(reason);
            return List.of();
        }
        Expectation awaited = AWAITED_COMMAND.get(saga.getState());
        if (awaited == null || rejectedCommand == null || !rejectedCommand.equals(awaited.command())) {
            return List.of();
        }
        if (awaited.reaction() == Reaction.COMPENSATE_AND_CANCEL) {
            return compensateAndCancel(saga, reason);
        }
        saga.pause(reason);
        return List.of();
    }

    private boolean isNothingToUndo(SagaInstance saga, String rejectedCommand) {
        for (Class<? extends SagaCommand> command : List.of(CancelBudgetIntegrationCommand.class, CancelPaymentIntegrationCommand.class)) {
            if (command.getSimpleName().equals(rejectedCommand) && saga.confirmCompensation(command)) {
                saga.settleCancellation();
                return true;
            }
        }
        return false;
    }

    private List<SagaCommand> compensateAndCancel(SagaInstance saga, String reason) {
        List<SagaCommand> commands = compensationPlanner.compensateFrom(SagaStep.REGISTRATION, saga, reason);
        saga.moveTo(SagaState.CANCELING);
        return commands;
    }
}
