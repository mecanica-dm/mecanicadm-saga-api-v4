package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.engine.SagaOrchestrator;
import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.RefundPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionEvent;
import com.mecanicadm.saga_api.core.saga.event.FinanceCommandRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentConfirmedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentFailedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentRefundedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderDiagnosedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionFinishedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionStartedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderVehicleDeliveredEvent;
import com.mecanicadm.saga_api.core.saga.engine.CompensationPlanner;
import com.mecanicadm.saga_api.testutils.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentCompensationTest {

    private final SagaOrchestrator orchestrator = SagaFlow.standard();
    private final UUID workOrderId = UUID.randomUUID();

    private SagaInstance inExecution() {
        SagaInstance saga = SagaInstance.start(created(workOrderId));
        orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId));
        orchestrator.handle(saga, new BudgetGeneratedEvent(workOrderId, new BigDecimal("720.00")));
        orchestrator.handle(saga, new BudgetSentForDecisionEvent(workOrderId));
        orchestrator.handle(saga, new BudgetApprovedEvent(workOrderId));
        orchestrator.handle(saga, new WorkOrderExecutionStartedEvent(workOrderId));
        return saga;
    }

    private SagaInstance awaitingPaymentLink() {
        SagaInstance saga = inExecution();
        orchestrator.handle(saga, new WorkOrderExecutionFinishedEvent(workOrderId));
        return saga;
    }

    private SagaInstance awaitingPayment() {
        SagaInstance saga = awaitingPaymentLink();
        orchestrator.handle(saga, new PaymentLinkGeneratedEvent(workOrderId, "https://pagamento.test/abc"));
        return saga;
    }

    private SagaInstance awaitingDelivery() {
        SagaInstance saga = awaitingPayment();
        orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));
        return saga;
    }

    private SagaInstance canceledAfterPayment() {
        SagaInstance saga = awaitingPayment();
        orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));
        orchestrator.handle(saga, new PaymentCanceledEvent(workOrderId));
        orchestrator.handle(saga, new BudgetCanceledEvent(workOrderId));
        assertEquals(SagaState.CANCELED, saga.getState());
        return saga;
    }

    private List<Class<?>> types(List<SagaCommand> commands) {
        return commands.stream().<Class<?>>map(SagaCommand::getClass).toList();
    }

    @Test
    @DisplayName("Deve cancelar o link de pagamento e o orcamento ao cancelar a OS enquanto o link e pedido")
    void shouldCancelPaymentAndBudgetWhenCanceledWhileAwaitingPaymentLink() {
        SagaInstance saga = awaitingPaymentLink();

        List<SagaCommand> commands = orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

        assertEquals(List.of(CancelPaymentIntegrationCommand.class, CancelBudgetIntegrationCommand.class), types(commands));
        assertEquals(SagaState.CANCELING, saga.getState());
    }

    @Test
    @DisplayName("Deve cancelar o link de pagamento e o orcamento ao cancelar a OS enquanto aguarda o pagamento")
    void shouldCancelPaymentAndBudgetWhenCanceledWhileAwaitingPayment() {
        List<SagaCommand> commands = orchestrator.handle(awaitingPayment(), new WorkOrderCanceledEvent(workOrderId));

        assertEquals(List.of(CancelPaymentIntegrationCommand.class, CancelBudgetIntegrationCommand.class), types(commands));
    }

    @Test
    @DisplayName("Deve estornar o pagamento e cancelar o orcamento ao cancelar a OS depois do pagamento confirmado")
    void shouldRefundAndCancelBudgetWhenCanceledAfterPayment() {
        SagaInstance saga = awaitingDelivery();

        List<SagaCommand> commands = orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

        assertEquals(List.of(RefundPaymentIntegrationCommand.class, CancelBudgetIntegrationCommand.class), types(commands));
        assertEquals(SagaState.CANCELING, saga.getState());
    }

    @Test
    @DisplayName("Deve cancelar o link ao cancelar a OS com a saga pausada por falha de pagamento")
    void shouldCancelPaymentWhenCanceledWhilePausedOnPayment() {
        SagaInstance saga = awaitingPayment();
        orchestrator.handle(saga, new PaymentFailedEvent(workOrderId, "Cartao recusado"));
        assertEquals(SagaState.PAUSED, saga.getState());

        List<SagaCommand> commands = orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

        assertEquals(List.of(CancelPaymentIntegrationCommand.class, CancelBudgetIntegrationCommand.class), types(commands));
    }

    @Test
    @DisplayName("Nao deve emitir command de pagamento ao cancelar a OS antes de o pagamento ser pedido")
    void shouldNotCompensatePaymentBeforePaymentIsRequested() {
        List<SagaCommand> commands = orchestrator.handle(inExecution(), new WorkOrderCanceledEvent(workOrderId));

        assertEquals(List.of(CancelBudgetIntegrationCommand.class), types(commands));
    }

    @Test
    @DisplayName("Deve registrar SAGA_COMPENSATED com o estorno e o orcamento ao cancelar depois do pagamento")
    void shouldLogRefundCompensation() {
        SagaInstance saga = awaitingDelivery();
        try (LogCapture logs = LogCapture.of(CompensationPlanner.class)) {
            orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

            String line = logs.single();
            assertTrue(line.contains("RefundPaymentIntegrationCommand"), line);
            assertTrue(line.contains("CancelBudgetIntegrationCommand"), line);
        }
    }

    @Test
    @DisplayName("Deve estornar o pagamento confirmado depois de a saga ser cancelada")
    void shouldRefundPaymentConfirmedAfterCancellation() {
        SagaInstance saga = canceledAfterPayment();

        List<SagaCommand> commands;
        try (LogCapture logs = LogCapture.of(CompensationPlanner.class)) {
            commands = orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));

            assertTrue(logs.single().contains("PaymentConfirmedAfterCancellation"), logs.lines().toString());
        }

        assertEquals(List.of(RefundPaymentIntegrationCommand.class), types(commands));
        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Deve estornar o pagamento confirmado enquanto a saga ainda esta cancelando, e o cancelamento passa a esperar o estorno")
    void shouldRefundPaymentConfirmedWhileCanceling() {
        SagaInstance saga = awaitingPayment();
        saga.moveTo(SagaState.CANCELING);

        List<SagaCommand> commands = orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));

        assertEquals(List.of(RefundPaymentIntegrationCommand.class), types(commands));
        assertEquals(SagaState.CANCELING, saga.getState());
        assertTrue(saga.isAwaitingCompensation(RefundPaymentIntegrationCommand.class));
        orchestrator.handle(saga, new PaymentRefundedEvent(workOrderId));
        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Deve cancelar o link gerado depois de a saga ser cancelada")
    void shouldCancelLinkGeneratedAfterCancellation() {
        SagaInstance saga = awaitingPaymentLink();
        orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));
        orchestrator.handle(saga, new PaymentCanceledEvent(workOrderId));
        orchestrator.handle(saga, new BudgetCanceledEvent(workOrderId));
        assertEquals(SagaState.CANCELED, saga.getState());

        List<SagaCommand> commands = orchestrator.handle(saga, new PaymentLinkGeneratedEvent(workOrderId, "https://pagamento.test/tarde"));

        assertEquals(List.of(CancelPaymentIntegrationCommand.class), types(commands));
        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Nao deve estornar um pagamento confirmado duplicado depois de a saga ser concluida")
    void shouldIgnorePaymentEventsAfterCompletion() {
        SagaInstance saga = awaitingDelivery();
        orchestrator.handle(saga, new WorkOrderVehicleDeliveredEvent(workOrderId));
        assertEquals(SagaState.COMPLETED, saga.getState());

        assertTrue(orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId)).isEmpty());
        assertTrue(orchestrator.handle(saga, new PaymentLinkGeneratedEvent(workOrderId, "x")).isEmpty());
    }

    @Test
    @DisplayName("Deve registrar SAGA_COMPENSATION_FAILED quando o Finance recusar o estorno, mesmo com a saga cancelada")
    void shouldLogWhenRefundIsRejectedAfterCancellation() {
        SagaInstance saga = canceledAfterPayment();
        try (LogCapture logs = LogCapture.of(com.mecanicadm.saga_api.core.saga.flow.CommandRejectionStep.class)) {
            List<SagaCommand> commands = orchestrator.handle(saga,
                    new FinanceCommandRejectedEvent(workOrderId, "RefundPaymentIntegrationCommand", "PaymentNotFound", "Pagamento nao encontrado"));

            String line = logs.single();
            assertTrue(line.startsWith("SAGA_COMPENSATION_FAILED workOrderId=" + workOrderId + " state=CANCELED"), line);
            assertTrue(line.contains("estorno manual necessario"), line);
            assertTrue(commands.isEmpty());
        }
    }

    @Test
    @DisplayName("Deve registrar SAGA_COMPENSATION_FAILED tambem quando o estorno for recusado com a saga ainda cancelando")
    void shouldLogWhenRefundIsRejectedWhileCanceling() {
        SagaInstance saga = awaitingDelivery();
        saga.moveTo(SagaState.CANCELING);
        try (LogCapture logs = LogCapture.of(com.mecanicadm.saga_api.core.saga.flow.CommandRejectionStep.class)) {
            orchestrator.handle(saga,
                    new FinanceCommandRejectedEvent(workOrderId, "RefundPaymentIntegrationCommand", "PaymentNotFound", "Pagamento nao encontrado"));

            assertTrue(logs.single().startsWith("SAGA_COMPENSATION_FAILED"), logs.lines().toString());
            assertEquals(SagaState.CANCELING, saga.getState());
        }
    }

    @Test
    @DisplayName("Nao deve tratar como falha a recusa do cancelamento do orcamento ou do link, que costuma indicar que nao havia o que cancelar")
    void shouldNotLogFailureForBenignCompensationRejections() {
        SagaInstance saga = canceledAfterPayment();
        try (LogCapture logs = LogCapture.of(com.mecanicadm.saga_api.core.saga.flow.CommandRejectionStep.class)) {
            orchestrator.handle(saga, new FinanceCommandRejectedEvent(workOrderId, "CancelBudgetIntegrationCommand", "BudgetNotFound", "x"));
            orchestrator.handle(saga, new FinanceCommandRejectedEvent(workOrderId, "CancelPaymentIntegrationCommand", "PaymentNotFound", "x"));

            assertTrue(logs.lines().isEmpty(), logs.lines().toString());
        }
    }
}
