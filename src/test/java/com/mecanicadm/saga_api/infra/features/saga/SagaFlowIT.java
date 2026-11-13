package com.mecanicadm.saga_api.infra.features.saga;

import com.mecanicadm.saga_api.core.saga.flow.SagaFlow;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaTransition;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaGateway;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetChangesRequestedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentConfirmedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderDiagnosedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionFinishedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionStartedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderVehicleDeliveredEvent;
import com.mecanicadm.saga_api.core.saga.exception.SagaExceptions;
import com.mecanicadm.saga_api.core.saga.usecase.ProcessSagaEventUseCase;
import com.mecanicadm.saga_api.infra.features.saga.persistence.SagaDocument;
import com.mecanicadm.saga_api.infra.messaging.outbox.OutboxDocument;
import com.mecanicadm.saga_api.infra.messaging.outbox.OutboxStore;
import com.mecanicadm.saga_api.testutils.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static com.mecanicadm.saga_api.testutils.SagaEvents.updated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SagaFlowIT extends AbstractIntegrationTest {

    @Autowired
    private ProcessSagaEventUseCase useCase;

    @Autowired
    private SagaGateway gateway;

    @Autowired
    private OutboxStore outboxStore;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final UUID workOrderId = UUID.randomUUID();

    @BeforeEach
    void cleanUp() {
        clearCollections(mongoTemplate);
    }

    private List<String> outboxTypes() {
        return outboxStore.findByAggregateId(workOrderId).stream().map(OutboxDocument::getType).toList();
    }

    private List<OutboxDocument> outbox() {
        return outboxStore.findByAggregateId(workOrderId);
    }

    private SagaDocument document() {
        return mongoTemplate.findById(workOrderId, SagaDocument.class);
    }

    @Test
    @DisplayName("Deve persistir a saga com o retrato da OS ao receber WorkOrderCreatedEvent")
    void shouldPersistSagaWithSnapshot() {
        useCase.execute(created(workOrderId));

        SagaInstance saga = gateway.findById(workOrderId).orElseThrow();
        assertEquals(SagaState.WORK_ORDER_OPEN, saga.getState());
        assertEquals("Deliveries Express", saga.getWorkOrder().client().name());
        assertEquals("ABC-1234", saga.getWorkOrder().vehicle().licensePlate());
        assertEquals(2, saga.getWorkOrder().laborItems().size());
        assertEquals(2, saga.getWorkOrder().materialItems().size());
        assertEquals(0, new BigDecimal("720.00").compareTo(saga.getWorkOrder().totalPrice()));
        assertTrue(outboxTypes().isEmpty());
    }

    @Test
    @DisplayName("Fluxo feliz: deve gravar na outbox um command por passo, na ordem, e concluir a saga")
    void shouldWriteOneCommandPerStepInOrder() {
        useCase.execute(created(workOrderId));
        useCase.execute(new WorkOrderDiagnosedEvent(workOrderId));
        useCase.execute(new BudgetGeneratedEvent(workOrderId, new BigDecimal("720.00")));
        useCase.execute(new BudgetSentForDecisionEvent(workOrderId));
        useCase.execute(new BudgetApprovedEvent(workOrderId));
        useCase.execute(new WorkOrderExecutionStartedEvent(workOrderId));
        useCase.execute(new WorkOrderExecutionFinishedEvent(workOrderId));
        useCase.execute(new PaymentLinkGeneratedEvent(workOrderId, "https://pagamento.test/abc"));
        useCase.execute(new PaymentConfirmedEvent(workOrderId));
        useCase.execute(new WorkOrderVehicleDeliveredEvent(workOrderId));

        assertEquals(SagaState.COMPLETED, document().getState());
        assertEquals(List.of("GenerateBudgetIntegrationCommand", "AuthorizeWorkOrderIntegrationCommand",
                "GeneratePaymentIntegrationCommand", "ReleaseWorkOrderVehicleIntegrationCommand"), outboxTypes());
        String payload = outbox().get(0).getPayload();
        assertTrue(payload.contains(workOrderId.toString()));
        assertTrue(payload.contains("\"workOrderId\""));
        assertTrue(payload.contains("\"workOrderSnapshot\":{"));
        assertTrue(payload.contains("Limpeza de Carbono"));
        assertTrue(payload.contains("Filtro de Oleo"));
    }

    @Test
    @DisplayName("Deve gravar as compensacoes pendentes no documento e so encerrar a saga quando forem confirmadas")
    void shouldPersistPendingCompensationsUntilConfirmed() {
        useCase.execute(created(workOrderId));
        useCase.execute(new WorkOrderDiagnosedEvent(workOrderId));
        useCase.execute(new BudgetGeneratedEvent(workOrderId, new BigDecimal("720.00")));
        useCase.execute(new BudgetSentForDecisionEvent(workOrderId));
        useCase.execute(new BudgetApprovedEvent(workOrderId));

        useCase.execute(new WorkOrderCanceledEvent(workOrderId));

        assertEquals(SagaState.CANCELING, document().getState());
        assertEquals(List.of("CancelBudgetIntegrationCommand"), document().getPendingCompensations());

        useCase.execute(new BudgetCanceledEvent(workOrderId));

        assertEquals(SagaState.CANCELED, document().getState());
        assertTrue(document().getPendingCompensations().isEmpty());
    }

    @Test
    @DisplayName("Deve gravar o historico de cada evento no documento da saga, com estado de origem, destino e commands")
    void shouldWriteHistoryForEveryEvent() {
        useCase.execute(created(workOrderId));
        useCase.execute(new WorkOrderDiagnosedEvent(workOrderId));

        List<SagaTransition> history = document().getHistory();

        assertEquals(2, history.size());
        assertEquals("WorkOrderCreatedEvent", history.get(0).eventType());
        assertNull(history.get(0).fromState());
        assertEquals(SagaState.WORK_ORDER_OPEN, history.get(0).toState());
        assertEquals("WorkOrderDiagnosedEvent", history.get(1).eventType());
        assertEquals(SagaState.WORK_ORDER_OPEN, history.get(1).fromState());
        assertEquals(SagaState.AWAITING_BUDGET, history.get(1).toState());
        assertEquals(List.of("GenerateBudgetIntegrationCommand"), history.get(1).commands());
    }

    @Test
    @DisplayName("Cenario 6: deve pedir mudancas na OS, cancelar o orcamento antigo na alteracao e pedir novo orcamento com o total atualizado")
    void shouldRenegotiateBudgetAfterChangesRequested() {
        useCase.execute(created(workOrderId));
        useCase.execute(new WorkOrderDiagnosedEvent(workOrderId));
        useCase.execute(new BudgetGeneratedEvent(workOrderId, new BigDecimal("720.00")));
        useCase.execute(new BudgetSentForDecisionEvent(workOrderId));
        useCase.execute(new BudgetChangesRequestedEvent(workOrderId, "Nao trocar filtro"));
        useCase.execute(updated(workOrderId));
        useCase.execute(new WorkOrderDiagnosedEvent(workOrderId));

        assertEquals(SagaState.AWAITING_BUDGET, document().getState());
        assertEquals(List.of("GenerateBudgetIntegrationCommand", "ChangesRequestedWorkOrderIntegrationCommand",
                "CancelBudgetIntegrationCommand", "GenerateBudgetIntegrationCommand"), outboxTypes());
        String firstBudget = outbox().get(0).getPayload();
        String secondBudget = outbox().get(3).getPayload();
        assertTrue(firstBudget.contains("Alinhamento de Rodas") && firstBudget.contains("Filtro de Oleo"));
        assertTrue(secondBudget.contains("Limpeza de Carbono") && secondBudget.contains("Oleo 5W30"));
        assertFalse(secondBudget.contains("Alinhamento de Rodas") || secondBudget.contains("Filtro de Oleo"));
    }

    @Test
    @DisplayName("Deve recusar com SagaNotFound o evento de uma OS cuja saga ainda nao existe")
    void shouldRejectEventWhenSagaDoesNotExist() {
        assertThrows(SagaExceptions.NotFound.class, () -> useCase.execute(new WorkOrderDiagnosedEvent(workOrderId)));

        assertEquals(0, mongoTemplate.count(new Query(), SagaDocument.class));
    }

    @Test
    @DisplayName("Deve ser idempotente ao receber WorkOrderCreatedEvent de uma OS que ja tem saga")
    void shouldKeepSagaWhenWorkOrderCreatedIsReceivedTwice() {
        useCase.execute(created(workOrderId));
        useCase.execute(new WorkOrderDiagnosedEvent(workOrderId));
        useCase.execute(created(workOrderId));

        assertEquals(SagaState.AWAITING_BUDGET, document().getState());
        assertEquals(List.of("GenerateBudgetIntegrationCommand"), outboxTypes());
    }

    @Test
    @DisplayName("Deve recusar com lock otimista o salvamento de uma saga desatualizada")
    void shouldRejectStaleSagaWithOptimisticLockError() {
        useCase.execute(created(workOrderId));
        SagaInstance stale = gateway.findById(workOrderId).orElseThrow();
        useCase.execute(new WorkOrderDiagnosedEvent(workOrderId));

        SagaFlow.standard().handle(stale, new WorkOrderDiagnosedEvent(workOrderId));

        assertThrows(OptimisticLockingFailureException.class, () -> gateway.save(stale));
        assertEquals(1, outbox().size());
        assertEquals(SagaState.AWAITING_BUDGET, document().getState());
    }

    @Test
    @DisplayName("Deve desfazer a saga e a outbox juntas quando a transacao falhar depois de gravar os dois")
    void shouldRollBackSagaAndOutboxTogetherWhenTransactionFails() {
        useCase.execute(created(workOrderId));

        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> template.executeWithoutResult(status -> {
            useCase.execute(new WorkOrderDiagnosedEvent(workOrderId));
            throw new IllegalStateException("falha depois de gravar");
        }));

        assertEquals(SagaState.WORK_ORDER_OPEN, document().getState());
        assertTrue(outbox().isEmpty());
    }
}
