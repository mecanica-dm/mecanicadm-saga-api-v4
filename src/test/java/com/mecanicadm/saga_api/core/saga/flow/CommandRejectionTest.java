package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.engine.SagaOrchestrator;
import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.RefundPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetChangesRequestedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionEvent;
import com.mecanicadm.saga_api.core.saga.event.FinanceCommandRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentConfirmedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGeneratedEvent;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRejectionTest {

    private final SagaOrchestrator orchestrator = SagaFlow.standard();
    private final UUID workOrderId = UUID.randomUUID();

    private SagaInstance awaitingBudget() {
        SagaInstance saga = SagaInstance.start(created(workOrderId));
        orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId));
        return saga;
    }

    private SagaInstance awaitingCustomerDecision() {
        SagaInstance saga = awaitingBudget();
        orchestrator.handle(saga, new BudgetGeneratedEvent(workOrderId, new BigDecimal("720.00")));
        orchestrator.handle(saga, new BudgetSentForDecisionEvent(workOrderId));
        return saga;
    }

    private SagaInstance awaitingExecution() {
        SagaInstance saga = awaitingCustomerDecision();
        orchestrator.handle(saga, new BudgetApprovedEvent(workOrderId));
        return saga;
    }

    private SagaInstance awaitingPaymentLink() {
        SagaInstance saga = awaitingExecution();
        orchestrator.handle(saga, new WorkOrderExecutionStartedEvent(workOrderId));
        orchestrator.handle(saga, new WorkOrderExecutionFinishedEvent(workOrderId));
        return saga;
    }

    private SagaInstance awaitingDelivery() {
        SagaInstance saga = awaitingPaymentLink();
        orchestrator.handle(saga, new PaymentLinkGeneratedEvent(workOrderId, "https://pagamento.test/abc"));
        orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));
        return saga;
    }

    private SagaInstance canceling() {
        SagaInstance saga = awaitingCustomerDecision();
        orchestrator.handle(saga, new BudgetRejectedEvent(workOrderId));
        return saga;
    }

    private WorkOrderCommandRejectedEvent workOrderRejection(String command) {
        return new WorkOrderCommandRejectedEvent(workOrderId, command, "InvalidStatusTransition", "Transicao invalida");
    }

    private FinanceCommandRejectedEvent financeRejection(String command) {
        return new FinanceCommandRejectedEvent(workOrderId, command, "BudgetNotFound", "Orcamento nao encontrado");
    }

    @Test
    @DisplayName("Deve desfazer o orcamento e cancelar a OS quando o Finance recusar o GenerateBudgetIntegrationCommand")
    void shouldCompensateAndCancelWhenFinanceRejectsGenerateBudget() {
        SagaInstance saga = awaitingBudget();

        List<SagaCommand> commands = orchestrator.handle(saga, financeRejection("GenerateBudgetIntegrationCommand"));

        assertEquals(List.of(CancelBudgetIntegrationCommand.class, CancelWorkOrderIntegrationCommand.class),
                commands.stream().map(SagaCommand::getClass).toList());
        assertEquals(SagaState.CANCELING, saga.getState());
        assertNull(saga.getPausedFrom());
    }

    @Test
    @DisplayName("Deve pausar a saga quando a Workorder recusar o proprio cancelamento, pois nao ha como compensar a compensacao")
    void shouldPauseWhenWorkOrderRejectsCancellation() {
        assertPausedFrom(canceling(), SagaState.CANCELING, "CancelWorkOrderIntegrationCommand");
    }

    @Test
    @DisplayName("Deve desfazer o orcamento e pedir o cancelamento da OS quando a Workorder recusar a autorizacao")
    void shouldCompensateAndCancelWhenWorkOrderRejectsAuthorization() {
        SagaInstance saga = awaitingExecution();

        List<SagaCommand> commands = orchestrator.handle(saga, workOrderRejection("AuthorizeWorkOrderIntegrationCommand"));

        assertEquals(List.of(CancelBudgetIntegrationCommand.class, CancelWorkOrderIntegrationCommand.class),
                commands.stream().map(SagaCommand::getClass).toList());
        assertEquals(SagaState.CANCELING, saga.getState());
        assertTrue(saga.isAwaitingCompensation(CancelBudgetIntegrationCommand.class));
        assertNull(saga.getPausedFrom());
    }

    @Test
    @DisplayName("Deve desfazer o orcamento e pedir o cancelamento da OS quando a Workorder recusar o pedido de alteracoes")
    void shouldCompensateAndCancelWhenWorkOrderRejectsChangesRequest() {
        SagaInstance saga = awaitingCustomerDecision();
        orchestrator.handle(saga, new BudgetChangesRequestedEvent(workOrderId, "Trocar o filtro"));

        List<SagaCommand> commands = orchestrator.handle(saga, workOrderRejection("ChangesRequestedWorkOrderIntegrationCommand"));

        assertEquals(List.of(CancelBudgetIntegrationCommand.class, CancelWorkOrderIntegrationCommand.class),
                commands.stream().map(SagaCommand::getClass).toList());
        assertEquals(SagaState.CANCELING, saga.getState());
    }

    @Test
    @DisplayName("Deve concluir o cancelamento so depois de a OS e o orcamento confirmarem, e pausar se a Workorder recusar tambem o cancelamento")
    void shouldFinishCancellationAfterCompensationOrPauseWhenCancellationIsRejected() {
        SagaInstance canceled = awaitingExecution();
        orchestrator.handle(canceled, workOrderRejection("AuthorizeWorkOrderIntegrationCommand"));
        orchestrator.handle(canceled, new WorkOrderCanceledEvent(workOrderId));
        assertEquals(SagaState.CANCELING, canceled.getState());
        orchestrator.handle(canceled, new BudgetCanceledEvent(workOrderId));

        SagaInstance rejected = awaitingExecution();
        orchestrator.handle(rejected, workOrderRejection("AuthorizeWorkOrderIntegrationCommand"));
        orchestrator.handle(rejected, workOrderRejection("CancelWorkOrderIntegrationCommand"));

        assertEquals(SagaState.CANCELED, canceled.getState());
        assertEquals(SagaState.PAUSED, rejected.getState());
        assertEquals(SagaState.CANCELING, rejected.getPausedFrom());
    }

    @Test
    @DisplayName("Deve desfazer o link, o orcamento e a OS quando o Finance recusar o GeneratePaymentIntegrationCommand")
    void shouldCompensateEverythingWhenPaymentIsRejected() {
        SagaInstance saga = awaitingPaymentLink();

        List<SagaCommand> commands = orchestrator.handle(saga, financeRejection("GeneratePaymentIntegrationCommand"));

        assertEquals(List.of(CancelPaymentIntegrationCommand.class, CancelBudgetIntegrationCommand.class,
                CancelWorkOrderIntegrationCommand.class), commands.stream().map(SagaCommand::getClass).toList());
        assertEquals(SagaState.CANCELING, saga.getState());
    }

    @Test
    @DisplayName("Deve estornar o pagamento, desfazer o orcamento e cancelar a OS quando a Workorder recusar a liberacao do veiculo")
    void shouldRefundAndCompensateWhenVehicleReleaseIsRejected() {
        SagaInstance saga = awaitingDelivery();

        List<SagaCommand> commands = orchestrator.handle(saga, workOrderRejection("ReleaseWorkOrderVehicleIntegrationCommand"));

        assertEquals(List.of(RefundPaymentIntegrationCommand.class, CancelBudgetIntegrationCommand.class,
                CancelWorkOrderIntegrationCommand.class), commands.stream().map(SagaCommand::getClass).toList());
        assertEquals(SagaState.CANCELING, saga.getState());
    }

    @Test
    @DisplayName("Deve registrar SAGA_PAUSED no log com a OS, a origem e o motivo ao pausar por recusa")
    void shouldLogWhenPausingOnRejection() {
        try (LogCapture logs = LogCapture.of(SagaOrchestrator.class)) {
            orchestrator.handle(canceling(), workOrderRejection("CancelWorkOrderIntegrationCommand"));

            String line = logs.single();
            assertTrue(line.startsWith("SAGA_PAUSED workOrderId=" + workOrderId + " pausedFrom=CANCELING"), line);
            assertTrue(line.contains("WorkOrderCommandRejected: CancelWorkOrderIntegrationCommand"), line);
        }
    }

    @Test
    @DisplayName("Deve registrar SAGA_COMPENSATED no log com o motivo e os commands de compensacao")
    void shouldLogWhenCompensating() {
        SagaInstance saga = awaitingExecution();
        try (LogCapture logs = LogCapture.of(CompensationPlanner.class)) {
            orchestrator.handle(saga, workOrderRejection("AuthorizeWorkOrderIntegrationCommand"));

            String line = logs.single();
            assertTrue(line.startsWith("SAGA_COMPENSATED workOrderId=" + workOrderId), line);
            assertTrue(line.contains("WorkOrderCommandRejected: AuthorizeWorkOrderIntegrationCommand"), line);
            assertTrue(line.contains("[CancelBudgetIntegrationCommand, CancelWorkOrderIntegrationCommand]"), line);
        }
    }

    @Test
    @DisplayName("Nao deve registrar SAGA_PAUSED quando a recusa e ignorada")
    void shouldNotLogWhenRejectionIsIgnored() {
        try (LogCapture logs = LogCapture.of(SagaOrchestrator.class)) {
            orchestrator.handle(awaitingCustomerDecision(), financeRejection("CancelBudgetIntegrationCommand"));

            assertTrue(logs.lines().isEmpty());
        }
    }

    private void assertPausedFrom(SagaInstance saga, SagaState expectedOrigin, String command) {
        orchestrator.handle(saga, workOrderRejection(command));

        assertEquals(SagaState.PAUSED, saga.getState());
        assertEquals(expectedOrigin, saga.getPausedFrom());
        assertEquals("WorkOrderCommandRejected: " + command + " - InvalidStatusTransition: Transicao invalida", saga.getPauseReason());
    }

    @Test
    @DisplayName("Deve so registrar no historico, sem pausar, a recusa de um command que o estado atual nao espera")
    void shouldIgnoreRejectionOfCommandNotAwaitedInCurrentState() {
        SagaInstance saga = awaitingCustomerDecision();

        orchestrator.handle(saga, financeRejection("CancelBudgetIntegrationCommand"));
        orchestrator.handle(saga, workOrderRejection("AuthorizeWorkOrderIntegrationCommand"));

        assertEquals(SagaState.AWAITING_CUSTOMER_DECISION, saga.getState());
        assertNull(saga.getPausedFrom());
        assertEquals("FinanceCommandRejectedEvent", saga.getHistory().get(saga.getHistory().size() - 2).eventType());
    }

    @Test
    @DisplayName("Deve ignorar a recusa sem o nome do command")
    void shouldIgnoreRejectionWithoutCommandName() {
        SagaInstance saga = awaitingBudget();

        orchestrator.handle(saga, new FinanceCommandRejectedEvent(workOrderId, null, "Erro", "Erro"));

        assertEquals(SagaState.AWAITING_BUDGET, saga.getState());
    }

    @Test
    @DisplayName("Deve ignorar a recusa quando a saga ja esta pausada ou encerrada")
    void shouldIgnoreRejectionWhenAlreadyPausedOrTerminal() {
        SagaInstance paused = canceling();
        orchestrator.handle(paused, workOrderRejection("CancelWorkOrderIntegrationCommand"));
        String reason = paused.getPauseReason();
        orchestrator.handle(paused, workOrderRejection("CancelWorkOrderIntegrationCommand"));

        SagaInstance canceled = canceling();
        orchestrator.handle(canceled, new WorkOrderCanceledEvent(workOrderId));
        orchestrator.handle(canceled, workOrderRejection("CancelWorkOrderIntegrationCommand"));

        assertEquals(reason, paused.getPauseReason());
        assertEquals(SagaState.CANCELING, paused.getPausedFrom());
        assertEquals(SagaState.CANCELED, canceled.getState());
    }

    @Test
    @DisplayName("Deve concluir o cancelamento de uma saga pausada por recusa quando a OS for cancelada")
    void shouldCancelPausedSagaWhenWorkOrderIsCanceled() {
        SagaInstance saga = canceling();
        orchestrator.handle(saga, workOrderRejection("CancelWorkOrderIntegrationCommand"));

        orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

        assertEquals(SagaState.CANCELED, saga.getState());
    }
}
