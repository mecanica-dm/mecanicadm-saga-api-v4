package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.engine.SagaOrchestrator;
import com.mecanicadm.saga_api.core.saga.command.AuthorizeWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.BudgetWorkOrderSnapshot;
import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.ChangesRequestedWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.GenerateBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.GeneratePaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.ReleaseWorkOrderVehicleIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaTransition;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetChangesRequestedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetExpiredEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionFailedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetUpdatedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentConfirmedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentFailedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGenerationFailedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderDiagnosedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionFinishedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionStartedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderVehicleDeliveredEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static com.mecanicadm.saga_api.testutils.SagaEvents.updated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SagaOrchestratorTest {

    private final SagaOrchestrator orchestrator = SagaFlow.standard();
    private final UUID workOrderId = UUID.randomUUID();

    private SagaInstance newSaga() {
        return SagaInstance.start(created(workOrderId));
    }

    private SagaInstance sagaIn(SagaState target) {
        SagaInstance saga = newSaga();
        if (target == SagaState.WORK_ORDER_OPEN) {
            return saga;
        }
        orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId));
        if (target == SagaState.AWAITING_BUDGET) {
            return saga;
        }
        orchestrator.handle(saga, new BudgetGeneratedEvent(workOrderId, new BigDecimal("720.00")));
        if (target == SagaState.BUDGET_GENERATED) {
            return saga;
        }
        orchestrator.handle(saga, new BudgetSentForDecisionEvent(workOrderId));
        if (target == SagaState.AWAITING_CUSTOMER_DECISION) {
            return saga;
        }
        orchestrator.handle(saga, new BudgetApprovedEvent(workOrderId));
        if (target == SagaState.AWAITING_EXECUTION) {
            return saga;
        }
        orchestrator.handle(saga, new WorkOrderExecutionStartedEvent(workOrderId));
        if (target == SagaState.IN_EXECUTION) {
            return saga;
        }
        orchestrator.handle(saga, new WorkOrderExecutionFinishedEvent(workOrderId));
        if (target == SagaState.AWAITING_PAYMENT_LINK) {
            return saga;
        }
        orchestrator.handle(saga, new PaymentLinkGeneratedEvent(workOrderId, "https://pagamento.test/abc"));
        if (target == SagaState.AWAITING_PAYMENT) {
            return saga;
        }
        orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));
        return saga;
    }

    @Test
    @DisplayName("Deve iniciar a saga aberta com o retrato da OS ao receber WorkOrderCreatedEvent")
    void shouldStartOpenWithWorkOrderSnapshot() {
        SagaInstance saga = newSaga();

        assertEquals(SagaState.WORK_ORDER_OPEN, saga.getState());
        assertEquals(workOrderId, saga.getWorkOrderId());
        assertEquals("Deliveries Express", saga.getWorkOrder().client().name());
        assertEquals(1, saga.getHistory().size());
    }

    @Test
    @DisplayName("Deve enviar o retrato da OS no pedido de orcamento ao diagnosticar")
    void shouldAskBudgetWithWorkOrderSnapshotWhenDiagnosed() {
        SagaInstance saga = newSaga();

        List<SagaCommand> commands = orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId));

        assertEquals(SagaState.AWAITING_BUDGET, saga.getState());
        GenerateBudgetIntegrationCommand command = assertInstanceOf(GenerateBudgetIntegrationCommand.class, commands.get(0));
        assertEquals(workOrderId, command.workOrderId());
        assertEquals(BudgetWorkOrderSnapshot.from(saga.getWorkOrder()), command.workOrderSnapshot());
        assertEquals(2, command.workOrderSnapshot().laborItems().size());
        assertEquals(2, command.workOrderSnapshot().materialItems().size());
        assertEquals(0, new BigDecimal("720.00").compareTo(command.workOrderSnapshot().totalPrice()));
    }

    @Test
    @DisplayName("Fluxo feliz: do diagnostico ate a entrega, com um command para cada passo e saga concluida")
    void shouldCompleteHappyPath() {
        SagaInstance saga = newSaga();

        assertInstanceOf(GenerateBudgetIntegrationCommand.class, orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId)).get(0));
        assertTrue(orchestrator.handle(saga, new BudgetGeneratedEvent(workOrderId, new BigDecimal("720.00"))).isEmpty());
        assertTrue(orchestrator.handle(saga, new BudgetSentForDecisionEvent(workOrderId)).isEmpty());
        assertEquals(SagaState.AWAITING_CUSTOMER_DECISION, saga.getState());
        assertInstanceOf(AuthorizeWorkOrderIntegrationCommand.class, orchestrator.handle(saga, new BudgetApprovedEvent(workOrderId)).get(0));
        assertTrue(orchestrator.handle(saga, new WorkOrderExecutionStartedEvent(workOrderId)).isEmpty());
        assertInstanceOf(GeneratePaymentIntegrationCommand.class, orchestrator.handle(saga, new WorkOrderExecutionFinishedEvent(workOrderId)).get(0));
        assertTrue(orchestrator.handle(saga, new PaymentLinkGeneratedEvent(workOrderId, "https://pagamento.test/abc")).isEmpty());
        assertEquals("https://pagamento.test/abc", saga.getPaymentLink());
        assertInstanceOf(ReleaseWorkOrderVehicleIntegrationCommand.class, orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId)).get(0));
        assertTrue(orchestrator.handle(saga, new WorkOrderVehicleDeliveredEvent(workOrderId)).isEmpty());

        assertEquals(SagaState.COMPLETED, saga.getState());
    }

    @Test
    @DisplayName("Cenario 1: alteracao da OS com orcamento gerado cancela o orcamento e reabre a saga")
    void shouldCancelBudgetWhenWorkOrderUpdatedAfterBudgetGenerated() {
        SagaInstance saga = sagaIn(SagaState.BUDGET_GENERATED);

        List<SagaCommand> commands = orchestrator.handle(saga, updated(workOrderId));

        assertEquals(SagaState.WORK_ORDER_OPEN, saga.getState());
        assertInstanceOf(CancelBudgetIntegrationCommand.class, commands.get(0));
        assertNull(saga.getBudgetTotal());
        assertTrue(saga.isAwaitingCompensation(CancelBudgetIntegrationCommand.class));
        assertEquals(0, new BigDecimal("430.00").compareTo(saga.getWorkOrder().totalPrice()));
    }

    @Test
    @DisplayName("Nao deve cancelar a OS quando o BudgetCanceledEvent for a resposta ao cancelamento pedido pela propria saga")
    void shouldIgnoreBudgetCanceledThatAnswersOwnCancellation() {
        SagaInstance saga = sagaIn(SagaState.BUDGET_GENERATED);
        orchestrator.handle(saga, updated(workOrderId));

        List<SagaCommand> commands = orchestrator.handle(saga, new BudgetCanceledEvent(workOrderId));

        assertTrue(commands.isEmpty());
        assertEquals(SagaState.WORK_ORDER_OPEN, saga.getState());
        assertFalse(saga.isAwaitingCompensation(CancelBudgetIntegrationCommand.class));
    }

    @Test
    @DisplayName("Deve pedir novo orcamento com o total atualizado no diagnostico seguinte a uma alteracao")
    void shouldAskNewBudgetAfterUpdateAndNewDiagnosis() {
        SagaInstance saga = sagaIn(SagaState.BUDGET_GENERATED);
        orchestrator.handle(saga, updated(workOrderId));

        List<SagaCommand> commands = orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId));

        GenerateBudgetIntegrationCommand command = assertInstanceOf(GenerateBudgetIntegrationCommand.class, commands.get(0));
        assertEquals(0, new BigDecimal("430.00").compareTo(command.workOrderSnapshot().totalPrice()));
        assertEquals(1, command.workOrderSnapshot().laborItems().size());
        assertEquals(SagaState.AWAITING_BUDGET, saga.getState());
    }

    @Test
    @DisplayName("Cenario 2: cancelamento da OS com orcamento enviado cancela o orcamento e encerra a saga quando o Finance confirma")
    void shouldCancelBudgetWhenWorkOrderCanceledAfterBudgetSent() {
        SagaInstance saga = sagaIn(SagaState.AWAITING_CUSTOMER_DECISION);

        List<SagaCommand> commands = orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

        assertInstanceOf(CancelBudgetIntegrationCommand.class, commands.get(0));
        assertEquals(SagaState.CANCELING, saga.getState());
        orchestrator.handle(saga, new BudgetCanceledEvent(workOrderId));
        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Cenario 3: falha no envio do orcamento pausa a saga sem emitir command, e o reenvio manual a retoma")
    void shouldPauseWhenBudgetSendFailsAndResumeWhenSentLater() {
        SagaInstance saga = sagaIn(SagaState.BUDGET_GENERATED);

        List<SagaCommand> commands = orchestrator.handle(saga, new BudgetSentForDecisionFailedEvent(workOrderId));

        assertTrue(commands.isEmpty());
        assertEquals(SagaState.PAUSED, saga.getState());
        assertEquals("BudgetSentForDecisionFailed", saga.getPauseReason());

        orchestrator.handle(saga, new BudgetSentForDecisionEvent(workOrderId));

        assertEquals(SagaState.AWAITING_CUSTOMER_DECISION, saga.getState());
        assertNull(saga.getPauseReason());
    }

    @Test
    @DisplayName("Cenario 4: falha na geracao do link de pagamento pausa a saga, e o link gerado depois a retoma")
    void shouldPauseWhenPaymentLinkFailsAndResumeWhenGeneratedLater() {
        SagaInstance saga = sagaIn(SagaState.AWAITING_PAYMENT_LINK);

        List<SagaCommand> commands = orchestrator.handle(saga, new PaymentLinkGenerationFailedEvent(workOrderId));

        assertTrue(commands.isEmpty());
        assertEquals(SagaState.PAUSED, saga.getState());

        orchestrator.handle(saga, new PaymentLinkGeneratedEvent(workOrderId, "https://pagamento.test/novo"));

        assertEquals(SagaState.AWAITING_PAYMENT, saga.getState());
    }

    @Test
    @DisplayName("Cenario 5: orcamento rejeitado pede o cancelamento da OS e conclui sem cancelar o orcamento de novo")
    void shouldCancelWorkOrderWhenBudgetRejected() {
        SagaInstance saga = sagaIn(SagaState.AWAITING_CUSTOMER_DECISION);

        List<SagaCommand> commands = orchestrator.handle(saga, new BudgetRejectedEvent(workOrderId));

        assertInstanceOf(CancelWorkOrderIntegrationCommand.class, commands.get(0));
        assertEquals(SagaState.CANCELING, saga.getState());

        List<SagaCommand> afterCanceled = orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

        assertTrue(afterCanceled.isEmpty());
        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Cenario 6: cliente pede alteracoes, a OS volta a edicao e a alteracao cancela o orcamento antigo uma unica vez")
    void shouldReopenWorkOrderWhenCustomerRequestsChanges() {
        SagaInstance saga = sagaIn(SagaState.AWAITING_CUSTOMER_DECISION);

        List<SagaCommand> commands = orchestrator.handle(saga, new BudgetChangesRequestedEvent(workOrderId, "Nao trocar filtro do ar condicionado"));

        ChangesRequestedWorkOrderIntegrationCommand command = assertInstanceOf(ChangesRequestedWorkOrderIntegrationCommand.class, commands.get(0));
        assertEquals("Nao trocar filtro do ar condicionado", command.observation());
        assertEquals(SagaState.CHANGES_REQUESTED, saga.getState());

        List<SagaCommand> firstUpdate = orchestrator.handle(saga, updated(workOrderId));
        List<SagaCommand> secondUpdate = orchestrator.handle(saga, updated(workOrderId));

        assertInstanceOf(CancelBudgetIntegrationCommand.class, firstUpdate.get(0));
        assertTrue(secondUpdate.isEmpty());
        assertEquals(SagaState.WORK_ORDER_OPEN, saga.getState());
    }

    @Test
    @DisplayName("Cenario 7: cancelamento da OS com servico em andamento cancela o orcamento e encerra a saga quando o Finance confirma")
    void shouldCancelBudgetWhenWorkOrderCanceledDuringExecution() {
        SagaInstance saga = sagaIn(SagaState.IN_EXECUTION);

        List<SagaCommand> commands = orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

        assertInstanceOf(CancelBudgetIntegrationCommand.class, commands.get(0));
        assertEquals(SagaState.CANCELING, saga.getState());
        orchestrator.handle(saga, new BudgetCanceledEvent(workOrderId));
        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Cenario 8: orcamento cancelado por expiracao pede o cancelamento da OS")
    void shouldCancelWorkOrderWhenBudgetCanceledByExpiration() {
        SagaInstance saga = sagaIn(SagaState.AWAITING_CUSTOMER_DECISION);

        List<SagaCommand> commands = orchestrator.handle(saga, new BudgetCanceledEvent(workOrderId));

        assertInstanceOf(CancelWorkOrderIntegrationCommand.class, commands.get(0));
        assertEquals(SagaState.CANCELING, saga.getState());
    }

    @Test
    @DisplayName("Deve pedir o cancelamento da OS quando o BudgetExpiredEvent chegar com o orcamento aguardando resposta")
    void shouldCancelWorkOrderWhenBudgetExpired() {
        SagaInstance saga = sagaIn(SagaState.AWAITING_CUSTOMER_DECISION);

        List<SagaCommand> commands = orchestrator.handle(saga, new BudgetExpiredEvent(workOrderId));

        assertInstanceOf(CancelWorkOrderIntegrationCommand.class, commands.get(0));
        assertEquals(SagaState.CANCELING, saga.getState());
    }

    @Test
    @DisplayName("Deve cancelar a OS aberta sem orcamento sem emitir command")
    void shouldCancelOpenWorkOrderWithoutCommands() {
        SagaInstance saga = newSaga();

        List<SagaCommand> commands = orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

        assertTrue(commands.isEmpty());
        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Deve atualizar o total do orcamento ao receber BudgetUpdatedEvent")
    void shouldUpdateBudgetTotalWhenBudgetUpdated() {
        SagaInstance saga = sagaIn(SagaState.AWAITING_CUSTOMER_DECISION);

        orchestrator.handle(saga, new BudgetUpdatedEvent(workOrderId, new BigDecimal("650.00"), "Desconto"));

        assertEquals(0, new BigDecimal("650.00").compareTo(saga.getBudgetTotal()));
        assertEquals(SagaState.AWAITING_CUSTOMER_DECISION, saga.getState());
    }

    @Test
    @DisplayName("Deve pausar a saga quando o pagamento falhar")
    void shouldPauseWhenPaymentFails() {
        SagaInstance saga = sagaIn(SagaState.AWAITING_PAYMENT);

        orchestrator.handle(saga, new PaymentFailedEvent(workOrderId, "cartao recusado"));

        assertEquals(SagaState.PAUSED, saga.getState());
        assertEquals("PaymentFailed: cartao recusado", saga.getPauseReason());
    }

    @Test
    @DisplayName("Deve ignorar evento atrasado ou duplicado, registrando a transicao sem mudar o estado")
    void shouldIgnoreEventNotApplicableToCurrentState() {
        SagaInstance saga = newSaga();
        orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId));

        List<SagaCommand> commands = orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId));

        assertTrue(commands.isEmpty());
        assertEquals(SagaState.AWAITING_BUDGET, saga.getState());
        SagaTransition transition = saga.getHistory().get(2);
        assertEquals("WorkOrderDiagnosedEvent", transition.eventType());
        assertEquals(transition.fromState(), transition.toState());
    }

    @Test
    @DisplayName("Deve ignorar qualquer evento depois de a saga estar concluida ou cancelada")
    void shouldIgnoreEventsAfterTerminalState() {
        SagaInstance canceled = newSaga();
        orchestrator.handle(canceled, new WorkOrderCanceledEvent(workOrderId));

        assertTrue(orchestrator.handle(canceled, new WorkOrderDiagnosedEvent(workOrderId)).isEmpty());
        assertTrue(orchestrator.handle(canceled, updated(workOrderId)).isEmpty());
        assertEquals(SagaState.CANCELED, canceled.getState());
    }

    @Test
    @DisplayName("Deve registrar o evento, os estados e os commands de cada transicao")
    void shouldRecordTransitionsWithCommands() {
        SagaInstance saga = newSaga();
        orchestrator.handle(saga, new WorkOrderDiagnosedEvent(workOrderId));

        List<SagaTransition> transitions = saga.getHistory();

        assertEquals(2, transitions.size());
        assertEquals(SagaState.WORK_ORDER_OPEN, transitions.get(1).fromState());
        assertEquals(SagaState.AWAITING_BUDGET, transitions.get(1).toState());
        assertEquals(List.of("GenerateBudgetIntegrationCommand"), transitions.get(1).commands());
    }

    @Test
    @DisplayName("Nao deve retomar a saga pausada no envio do orcamento com o link de pagamento gerado")
    void shouldNotResumeBudgetPauseWithPaymentLinkEvent() {
        SagaInstance saga = sagaIn(SagaState.BUDGET_GENERATED);
        orchestrator.handle(saga, new BudgetSentForDecisionFailedEvent(workOrderId));

        orchestrator.handle(saga, new PaymentLinkGeneratedEvent(workOrderId, "https://pagamento.test/x"));

        assertEquals(SagaState.PAUSED, saga.getState());
        assertEquals(SagaState.BUDGET_GENERATED, saga.getPausedFrom());
    }

    @Test
    @DisplayName("Deve retomar a saga pausada por falha de pagamento quando o pagamento for confirmado")
    void shouldResumePaymentPauseWhenPaymentConfirmed() {
        SagaInstance saga = sagaIn(SagaState.AWAITING_PAYMENT);
        orchestrator.handle(saga, new PaymentFailedEvent(workOrderId, "cartao recusado"));

        List<SagaCommand> commands = orchestrator.handle(saga, new PaymentConfirmedEvent(workOrderId));

        assertInstanceOf(ReleaseWorkOrderVehicleIntegrationCommand.class, commands.get(0));
        assertEquals(SagaState.AWAITING_DELIVERY, saga.getState());
        assertNull(saga.getPausedFrom());
    }

    @Test
    @DisplayName("Deve cancelar o orcamento ao cancelar a OS mesmo com a saga pausada em uma etapa com orcamento")
    void shouldCancelBudgetWhenWorkOrderCanceledWhilePaused() {
        SagaInstance saga = sagaIn(SagaState.BUDGET_GENERATED);
        orchestrator.handle(saga, new BudgetSentForDecisionFailedEvent(workOrderId));

        List<SagaCommand> commands = orchestrator.handle(saga, new WorkOrderCanceledEvent(workOrderId));

        assertInstanceOf(CancelBudgetIntegrationCommand.class, commands.get(0));
        assertEquals(SagaState.CANCELING, saga.getState());
        orchestrator.handle(saga, new BudgetCanceledEvent(workOrderId));
        assertEquals(SagaState.CANCELED, saga.getState());
    }

    @Test
    @DisplayName("Deve apenas atualizar o retrato da OS quando ela for alterada apos o orcamento aprovado")
    void shouldOnlyRefreshSnapshotWhenWorkOrderUpdatedAfterApproval() {
        SagaInstance saga = sagaIn(SagaState.AWAITING_EXECUTION);

        List<SagaCommand> commands = orchestrator.handle(saga, updated(workOrderId));

        assertTrue(commands.isEmpty());
        assertEquals(SagaState.AWAITING_EXECUTION, saga.getState());
        assertEquals(0, new BigDecimal("430.00").compareTo(saga.getWorkOrder().totalPrice()));
    }
}
