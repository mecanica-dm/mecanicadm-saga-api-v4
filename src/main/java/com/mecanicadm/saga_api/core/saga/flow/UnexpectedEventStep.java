package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetChangesRequestedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetExpiredEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentConfirmedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentFailedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGenerationFailedEvent;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionFinishedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionStartedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderVehicleDeliveredEvent;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.engine.TransitionRule;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class UnexpectedEventStep implements SagaFlowStep {

    private static final Map<Class<? extends SagaEvent>, SagaStep> STEP_OF_EVENT = Map.<Class<? extends SagaEvent>, SagaStep>ofEntries(
            Map.entry(BudgetApprovedEvent.class, SagaStep.CUSTOMER_DECISION),
            Map.entry(BudgetRejectedEvent.class, SagaStep.CUSTOMER_DECISION),
            Map.entry(BudgetExpiredEvent.class, SagaStep.CUSTOMER_DECISION),
            Map.entry(BudgetChangesRequestedEvent.class, SagaStep.CUSTOMER_DECISION),
            Map.entry(WorkOrderExecutionStartedEvent.class, SagaStep.EXECUTION),
            Map.entry(WorkOrderExecutionFinishedEvent.class, SagaStep.EXECUTION),
            Map.entry(PaymentLinkGeneratedEvent.class, SagaStep.PAYMENT),
            Map.entry(PaymentLinkGenerationFailedEvent.class, SagaStep.PAYMENT),
            Map.entry(PaymentConfirmedEvent.class, SagaStep.PAYMENT),
            Map.entry(PaymentFailedEvent.class, SagaStep.PAYMENT),
            Map.entry(WorkOrderVehicleDeliveredEvent.class, SagaStep.DELIVERY));

    @Override
    public List<TransitionRule<?>> rules() {
        return STEP_OF_EVENT.entrySet().stream()
                .<TransitionRule<?>>map(entry -> ruleFor(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static <E extends SagaEvent> TransitionRule<E> ruleFor(Class<E> eventType, SagaStep eventStep) {
        return TransitionRule.on(eventType, isAheadOf(eventStep), UnexpectedEventStep::pause);
    }

    private static Predicate<SagaInstance> isAheadOf(SagaStep eventStep) {
        return saga -> {
            SagaStep current = saga.currentStep();
            if (saga.isIn(SagaState.PAUSED) || current == null || eventStep.ordinal() <= current.ordinal()) {
                return false;
            }
            boolean answersOwnBudgetCancellation = saga.isAwaitingCompensation(CancelBudgetIntegrationCommand.class) && eventStep == SagaStep.CUSTOMER_DECISION;
            return !answersOwnBudgetCancellation;
        };
    }

    private static List<SagaCommand> pause(SagaInstance saga, SagaEvent event) {
        saga.pause("UnexpectedEvent: %s recebido no estado %s (etapa %s)"
                .formatted(event.getClass().getSimpleName(), saga.getState(), saga.currentStep()));
        return List.of();
    }
}
