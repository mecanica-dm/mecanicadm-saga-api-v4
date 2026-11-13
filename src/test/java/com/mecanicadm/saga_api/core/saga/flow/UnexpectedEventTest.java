package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.engine.SagaOrchestrator;
import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentConfirmedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderDiagnosedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionStartedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderVehicleDeliveredEvent;
import com.mecanicadm.saga_api.testutils.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static com.mecanicadm.saga_api.testutils.SagaEvents.updated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnexpectedEventTest {

    private final SagaOrchestrator orchestrator = SagaFlow.standard();
    private final UUID workOrderId = UUID.randomUUID();

    private SagaInstance open() {
        return SagaInstance.start(created(workOrderId));
    }

    private SagaInstance awaitingBudget() {
        SagaInstance saga = open();
        orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId));
        return saga;
    }

    private SagaInstance awaitingCustomerDecision() {
        SagaInstance saga = awaitingBudget();
        orchestrator.handle(saga, new BudgetGeneratedEvent(workOrderId, new BigDecimal("720.00")));
        orchestrator.handle(saga, new BudgetSentForDecisionEvent(workOrderId));
        return saga;
    }

    private SagaInstance inExecution() {
        SagaInstance saga = awaitingCustomerDecision();
        orchestrator.handle(saga, new BudgetApprovedEvent(workOrderId));
        orchestrator.handle(saga, new WorkOrderExecutionStartedEvent(workOrderId));
        return saga;
    }

    @Test
    @DisplayName("Deve pausar com o motivo quando o pagamento for confirmado antes de a OS ser diagnosticada")
    void shouldPauseWhenEventIsAheadOfSaga() {
        SagaInstance saga = open();

        List<SagaCommand> commands = orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));

        assertTrue(commands.isEmpty());
        assertEquals(SagaState.PAUSED, saga.getState());
        assertEquals(SagaState.WORK_ORDER_OPEN, saga.getPausedFrom());
        assertEquals("UnexpectedEvent: PaymentConfirmedEvent recebido no estado WORK_ORDER_OPEN (etapa REGISTRATION)",
                saga.getPauseReason());
    }

    @Test
    @DisplayName("Deve pausar quando a execucao comecar sem o orcamento ter sido aprovado")
    void shouldPauseWhenExecutionStartsWithoutApproval() {
        SagaInstance saga = awaitingCustomerDecision();

        orchestrator.handle(saga, new WorkOrderExecutionStartedEvent(workOrderId));

        assertEquals(SagaState.PAUSED, saga.getState());
        assertEquals(SagaState.AWAITING_CUSTOMER_DECISION, saga.getPausedFrom());
    }

    @Test
    @DisplayName("Deve pausar quando a aprovacao do orcamento chegar com a saga ainda esperando o orcamento")
    void shouldPauseWhenApprovalArrivesBeforeBudget() {
        SagaInstance saga = awaitingBudget();

        orchestrator.handle(saga, new BudgetApprovedEvent(workOrderId));

        assertEquals(SagaState.PAUSED, saga.getState());
        assertEquals(SagaState.AWAITING_BUDGET, saga.getPausedFrom());
    }

    @Test
    @DisplayName("Deve registrar SAGA_PAUSED no log ao pausar por evento inesperado")
    void shouldLogUnexpectedEvent() {
        try (LogCapture logs = LogCapture.of(SagaOrchestrator.class)) {
            orchestrator.handle(open(), new WorkOrderVehicleDeliveredEvent(workOrderId));

            String line = logs.single();
            assertTrue(line.startsWith("SAGA_PAUSED workOrderId=" + workOrderId + " pausedFrom=WORK_ORDER_OPEN"), line);
            assertTrue(line.contains("event=WorkOrderVehicleDeliveredEvent"), line);
            assertTrue(line.contains("UnexpectedEvent"), line);
        }
    }

    @Test
    @DisplayName("Deve ignorar, sem pausar, evento duplicado ou de uma etapa que a saga ja passou")
    void shouldIgnoreStaleAndDuplicateEvents() {
        SagaInstance saga = inExecution();

        orchestrator.handle(saga, new BudgetApprovedEvent(workOrderId));
        orchestrator.handle(saga, new WorkOrderExecutionStartedEvent(workOrderId));

        assertEquals(SagaState.IN_EXECUTION, saga.getState());
        assertNull(saga.getPausedFrom());
    }

    @Test
    @DisplayName("Nao deve pausar com orcamento chegando depois de a OS ser alterada, pois e a resposta ao pedido da propria saga")
    void shouldNotPauseOnBudgetEventsAfterOwnCancellation() {
        SagaInstance saga = awaitingCustomerDecision();
        orchestrator.handle(saga, updated(workOrderId));
        assertTrue(saga.isAwaitingCompensation(CancelBudgetIntegrationCommand.class));

        orchestrator.handle(saga, new BudgetGeneratedEvent(workOrderId, new BigDecimal("720.00")));
        orchestrator.handle(saga, new BudgetApprovedEvent(workOrderId));

        assertEquals(SagaState.WORK_ORDER_OPEN, saga.getState());
    }

    @Test
    @DisplayName("Nao deve pausar uma saga que ja esta pausada nem sobrescrever o ponto de origem da pausa")
    void shouldNotPauseAnAlreadyPausedSaga() {
        SagaInstance saga = open();
        orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));
        String reason = saga.getPauseReason();

        orchestrator.handle(saga, new WorkOrderVehicleDeliveredEvent(workOrderId));

        assertEquals(SagaState.PAUSED, saga.getState());
        assertEquals(SagaState.WORK_ORDER_OPEN, saga.getPausedFrom());
        assertEquals(reason, saga.getPauseReason());
    }

    @Test
    @DisplayName("Nao deve pausar uma saga em cancelamento")
    void shouldNotPauseWhenCanceling() {
        SagaInstance saga = awaitingCustomerDecision();
        saga.moveTo(SagaState.CANCELING);

        orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));

        assertEquals(SagaState.CANCELING, saga.getState());
    }

    @Test
    @DisplayName("Deve permitir cancelar a OS de uma saga pausada por evento inesperado")
    void shouldCancelSagaPausedByUnexpectedEvent() {
        SagaInstance saga = open();
        orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));

        orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

        assertEquals(SagaState.CANCELED, saga.getState());
    }
}
