package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.RefundPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.engine.SagaOrchestrator;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionEvent;
import com.mecanicadm.saga_api.core.saga.event.FinanceCommandRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentConfirmedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentRefundedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCommandRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderDiagnosedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionFinishedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionStartedEvent;
import com.mecanicadm.saga_api.core.saga.engine.CompensationPlanner;
import com.mecanicadm.saga_api.testutils.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompensationConfirmationTest {

    private final SagaOrchestrator orchestrator = SagaFlow.standard();
    private final UUID workOrderId = UUID.randomUUID();

    private SagaInstance awaitingExecution() {
        SagaInstance saga = SagaInstance.start(created(workOrderId));
        orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId));
        orchestrator.handle(saga, new BudgetGeneratedEvent(workOrderId, new BigDecimal("720.00")));
        orchestrator.handle(saga, new BudgetSentForDecisionEvent(workOrderId));
        orchestrator.handle(saga, new BudgetApprovedEvent(workOrderId));
        return saga;
    }

    private SagaInstance awaitingPayment() {
        SagaInstance saga = awaitingExecution();
        orchestrator.handle(saga, new WorkOrderExecutionStartedEvent(workOrderId));
        orchestrator.handle(saga, new WorkOrderExecutionFinishedEvent(workOrderId));
        orchestrator.handle(saga, new PaymentLinkGeneratedEvent(workOrderId, "https://pagamento.test/abc"));
        return saga;
    }

    private SagaInstance awaitingDelivery() {
        SagaInstance saga = awaitingPayment();
        orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));
        return saga;
    }

    private void cancel(SagaInstance saga) {
        orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));
    }

    @Test
    @DisplayName("Deve esperar o link e o orcamento serem cancelados, em qualquer ordem, antes de encerrar a saga")
    void shouldWaitForEveryCompensationInAnyOrder() {
        SagaInstance first = awaitingPayment();
        SagaInstance second = awaitingPayment();
        cancel(first);
        cancel(second);

        orchestrator.handle(first, new PaymentCanceledEvent(workOrderId));
        assertEquals(SagaState.CANCELING, first.getState());
        orchestrator.handle(first, new BudgetCanceledEvent(workOrderId));

        orchestrator.handle(second, new BudgetCanceledEvent(workOrderId));
        assertEquals(SagaState.CANCELING, second.getState());
        orchestrator.handle(second, new PaymentCanceledEvent(workOrderId));

        assertEquals(SagaState.CANCELED, first.getState());
        assertEquals(SagaState.CANCELED, second.getState());
        assertFalse(first.hasPendingCompensations());
    }

    @Test
    @DisplayName("Deve esperar o estorno e o cancelamento do orcamento antes de encerrar a saga cancelada depois do pagamento")
    void shouldWaitForRefundAndBudgetAfterPayment() {
        SagaInstance saga = awaitingDelivery();
        cancel(saga);

        assertEquals(List.of("RefundPaymentIntegrationCommand", "CancelBudgetIntegrationCommand"), saga.getPendingCompensations());
        orchestrator.handle(saga, new BudgetCanceledEvent(workOrderId));
        assertEquals(SagaState.CANCELING, saga.getState());
        orchestrator.handle(saga, new PaymentRefundedEvent(workOrderId));

        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Deve esperar a OS e o orcamento quando a propria saga pediu o cancelamento por causa de uma recusa")
    void shouldWaitForWorkOrderAndBudgetWhenSagaStartedTheCancellation() {
        SagaInstance saga = awaitingExecution();
        orchestrator.handle(saga, new WorkOrderCommandRejectedEvent(workOrderId, "AuthorizeWorkOrderIntegrationCommand", "Erro", "x"));

        assertTrue(saga.isAwaitingCompensation(CancelWorkOrderIntegrationCommand.class));
        assertTrue(saga.isAwaitingCompensation(CancelBudgetIntegrationCommand.class));
        orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));
        assertEquals(SagaState.CANCELING, saga.getState());
        orchestrator.handle(saga, new BudgetCanceledEvent(workOrderId));

        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Deve encerrar de uma vez o cancelamento da OS quando nao ha nada a compensar")
    void shouldCancelAtOnceWhenThereIsNothingToCompensate() {
        SagaInstance saga = SagaInstance.start(created(workOrderId));

        cancel(saga);

        assertEquals(SagaState.CANCELED, saga.getState());
        assertFalse(saga.hasPendingCompensations());
    }

    @Test
    @DisplayName("Deve ignorar a confirmacao de uma compensacao que nao esta pendente, sem mudar o estado")
    void shouldIgnoreConfirmationThatIsNotPending() {
        SagaInstance saga = awaitingPayment();

        orchestrator.handle(saga, new PaymentCanceledEvent(workOrderId));
        orchestrator.handle(saga, new PaymentRefundedEvent(workOrderId));

        assertEquals(SagaState.AWAITING_PAYMENT, saga.getState());
        assertFalse(saga.hasPendingCompensations());
    }

    @Test
    @DisplayName("Deve ignorar a confirmacao duplicada sem encerrar o cancelamento antes da hora")
    void shouldIgnoreDuplicatedConfirmation() {
        SagaInstance saga = awaitingPayment();
        cancel(saga);

        orchestrator.handle(saga, new PaymentCanceledEvent(workOrderId));
        orchestrator.handle(saga, new PaymentCanceledEvent(workOrderId));

        assertEquals(SagaState.CANCELING, saga.getState());
        assertEquals(List.of("CancelBudgetIntegrationCommand"), saga.getPendingCompensations());
    }

    @Test
    @DisplayName("Deve dar o cancelamento do orcamento ou do link por concluido quando o Finance recusa por nao haver o que cancelar")
    void shouldTreatRejectionOfBudgetAndLinkCancellationAsNothingToUndo() {
        SagaInstance saga = awaitingPayment();
        cancel(saga);

        orchestrator.handle(saga, new FinanceCommandRejectedEvent(workOrderId, "CancelPaymentIntegrationCommand", "PaymentNotFound", "x"));
        assertEquals(SagaState.CANCELING, saga.getState());
        orchestrator.handle(saga, new FinanceCommandRejectedEvent(workOrderId, "CancelBudgetIntegrationCommand", "BudgetNotFound", "x"));

        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Deve pausar a saga quando o Finance recusar o estorno, e encerrar quando o estorno for confirmado depois")
    void shouldPauseWhenRefundIsRejectedAndFinishWhenRefundedLater() {
        SagaInstance saga = awaitingDelivery();
        cancel(saga);
        orchestrator.handle(saga, new BudgetCanceledEvent(workOrderId));

        try (LogCapture logs = LogCapture.of(com.mecanicadm.saga_api.core.saga.engine.SagaOrchestrator.class)) {
            orchestrator.handle(saga, new FinanceCommandRejectedEvent(workOrderId, "RefundPaymentIntegrationCommand", "PaymentNotFound", "x"));

            assertTrue(logs.single().startsWith("SAGA_PAUSED workOrderId=" + workOrderId + " pausedFrom=CANCELING"), logs.lines().toString());
        }
        assertEquals(SagaState.PAUSED, saga.getState());
        assertEquals(SagaState.CANCELING, saga.getPausedFrom());
        assertTrue(saga.isAwaitingCompensation(RefundPaymentIntegrationCommand.class));

        orchestrator.handle(saga, new PaymentRefundedEvent(workOrderId));

        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Nao deve pausar de novo a saga ja pausada por um estorno recusado")
    void shouldNotPauseAgainWhenRefundIsRejectedTwice() {
        SagaInstance saga = awaitingDelivery();
        cancel(saga);
        orchestrator.handle(saga, new FinanceCommandRejectedEvent(workOrderId, "RefundPaymentIntegrationCommand", "E", "x"));
        String reason = saga.getPauseReason();

        orchestrator.handle(saga, new FinanceCommandRejectedEvent(workOrderId, "RefundPaymentIntegrationCommand", "Outro", "y"));

        assertEquals(SagaState.PAUSED, saga.getState());
        assertEquals(SagaState.CANCELING, saga.getPausedFrom());
        assertEquals(reason, saga.getPauseReason());
    }

    @Test
    @DisplayName("Deve registrar SAGA_COMPENSATED ao enviar as compensacoes e deixar a saga pendente ate a confirmacao")
    void shouldLogCompensationAndKeepItPending() {
        SagaInstance saga = awaitingPayment();
        try (LogCapture logs = LogCapture.of(CompensationPlanner.class)) {
            cancel(saga);

            assertTrue(logs.single().contains("[CancelPaymentIntegrationCommand, CancelBudgetIntegrationCommand]"), logs.lines().toString());
        }
        assertEquals(SagaState.CANCELING, saga.getState());
    }
}
