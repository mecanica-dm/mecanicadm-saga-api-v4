package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.command.CancelPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.RefundPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.ReleaseWorkOrderVehicleIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.core.saga.event.PaymentCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentConfirmedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentFailedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentRefundedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGenerationFailedEvent;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.engine.TransitionRule;
import com.mecanicadm.saga_api.core.saga.engine.Compensation;
import com.mecanicadm.saga_api.core.saga.engine.CompensationPlanner;

import java.util.List;

import static com.mecanicadm.saga_api.core.saga.engine.Guards.awaitingCompensation;
import static com.mecanicadm.saga_api.core.saga.engine.Guards.inState;
import static com.mecanicadm.saga_api.core.saga.engine.Guards.pausedFrom;

public class PaymentStep implements SagaFlowStep {

    @Override
    public List<TransitionRule<?>> rules() {
        return List.of(
                TransitionRule.on(PaymentLinkGeneratedEvent.class, inState(SagaState.AWAITING_PAYMENT_LINK), this::awaitPayment),
                TransitionRule.on(PaymentLinkGeneratedEvent.class, pausedFrom(SagaState.AWAITING_PAYMENT_LINK), this::awaitPayment),
                TransitionRule.on(PaymentLinkGenerationFailedEvent.class, inState(SagaState.AWAITING_PAYMENT_LINK), this::pauseLinkGeneration),
                TransitionRule.on(PaymentConfirmedEvent.class, inState(SagaState.AWAITING_PAYMENT), this::releaseVehicle),
                TransitionRule.on(PaymentConfirmedEvent.class, pausedFrom(SagaState.AWAITING_PAYMENT), this::releaseVehicle),
                TransitionRule.on(PaymentFailedEvent.class, inState(SagaState.AWAITING_PAYMENT), this::pausePayment),
                TransitionRule.on(PaymentCanceledEvent.class, awaitingCompensation(CancelPaymentIntegrationCommand.class), this::confirmPaymentCancellation),
                TransitionRule.on(PaymentRefundedEvent.class, awaitingCompensation(RefundPaymentIntegrationCommand.class), this::confirmRefund),
                TransitionRule.afterTermination(PaymentConfirmedEvent.class, inState(SagaState.CANCELING, SagaState.CANCELED), this::refundLatePayment),
                TransitionRule.afterTermination(PaymentLinkGeneratedEvent.class, inState(SagaState.CANCELING, SagaState.CANCELED), this::cancelLateLink));
    }

    @Override
    public List<Compensation> compensations() {
        return List.of(
                Compensation.of(SagaStep.PAYMENT, SagaInstance::hasPendingPayment,
                        saga -> new CancelPaymentIntegrationCommand(saga.getWorkOrderId())),
                Compensation.of(SagaStep.DELIVERY, SagaInstance::hasConfirmedPayment,
                        saga -> new RefundPaymentIntegrationCommand(saga.getWorkOrderId())));
    }

    private List<SagaCommand> confirmPaymentCancellation(SagaInstance saga, PaymentCanceledEvent event) {
        saga.confirmCompensation(CancelPaymentIntegrationCommand.class);
        saga.settleCancellation();
        return List.of();
    }

    private List<SagaCommand> confirmRefund(SagaInstance saga, PaymentRefundedEvent event) {
        saga.confirmCompensation(RefundPaymentIntegrationCommand.class);
        saga.settleCancellation();
        return List.of();
    }

    private List<SagaCommand> refundLatePayment(SagaInstance saga, PaymentConfirmedEvent event) {
        return compensateLate(saga, new RefundPaymentIntegrationCommand(saga.getWorkOrderId()), "PaymentConfirmedAfterCancellation");
    }

    private List<SagaCommand> cancelLateLink(SagaInstance saga, PaymentLinkGeneratedEvent event) {
        return compensateLate(saga, new CancelPaymentIntegrationCommand(saga.getWorkOrderId()), "PaymentLinkGeneratedAfterCancellation");
    }

    private List<SagaCommand> compensateLate(SagaInstance saga, SagaCommand command, String reason) {
        List<SagaCommand> commands = List.of(command);
        if (!saga.isTerminal()) {
            saga.expectCompensation(command);
        }
        CompensationPlanner.logCompensation(saga, reason, commands);
        return commands;
    }

    private List<SagaCommand> awaitPayment(SagaInstance saga, PaymentLinkGeneratedEvent event) {
        saga.registerPaymentLink(event.paymentLink());
        saga.moveTo(SagaState.AWAITING_PAYMENT);
        return List.of();
    }

    private List<SagaCommand> pauseLinkGeneration(SagaInstance saga, PaymentLinkGenerationFailedEvent event) {
        saga.pause("PaymentLinkGenerationFailed");
        return List.of();
    }

    private List<SagaCommand> releaseVehicle(SagaInstance saga, PaymentConfirmedEvent event) {
        saga.moveTo(SagaState.AWAITING_DELIVERY);
        return List.of(new ReleaseWorkOrderVehicleIntegrationCommand(saga.getWorkOrderId()));
    }

    private List<SagaCommand> pausePayment(SagaInstance saga, PaymentFailedEvent event) {
        saga.pause("PaymentFailed: " + event.reason());
        return List.of();
    }
}
