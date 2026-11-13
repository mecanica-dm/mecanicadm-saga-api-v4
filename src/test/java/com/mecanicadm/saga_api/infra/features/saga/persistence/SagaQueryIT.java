package com.mecanicadm.saga_api.infra.features.saga.persistence;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaQueryGateway;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderDiagnosedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionFailedEvent;
import com.mecanicadm.saga_api.core.saga.usecase.GetAllSagasUseCase;
import com.mecanicadm.saga_api.core.saga.usecase.ProcessSagaEventUseCase;
import com.mecanicadm.saga_api.core.saga.usecase.query.GetAllSagasQuery;
import com.mecanicadm.saga_api.core.shared.PageResult;
import com.mecanicadm.saga_api.testutils.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class SagaQueryIT extends AbstractIntegrationTest {

    @Autowired
    private ProcessSagaEventUseCase process;
    @Autowired
    private SagaQueryGateway queryGateway;
    @Autowired
    private GetAllSagasUseCase getAll;
    @Autowired
    private MongoTemplate mongoTemplate;

    private final UUID open = UUID.randomUUID();
    private final UUID awaitingBudget = UUID.randomUUID();
    private final UUID awaitingDecision = UUID.randomUUID();
    private final UUID pausedInBudget = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clearCollections(mongoTemplate);
        process.execute(created(open));

        process.execute(created(awaitingBudget));
        process.execute(new WorkOrderDiagnosedEvent(awaitingBudget));

        process.execute(created(awaitingDecision));
        process.execute(new WorkOrderDiagnosedEvent(awaitingDecision));
        process.execute(new BudgetGeneratedEvent(awaitingDecision, new BigDecimal("720.00")));
        process.execute(new BudgetSentForDecisionEvent(awaitingDecision));

        process.execute(created(pausedInBudget));
        process.execute(new WorkOrderDiagnosedEvent(pausedInBudget));
        process.execute(new BudgetGeneratedEvent(pausedInBudget, new BigDecimal("720.00")));
        process.execute(new BudgetSentForDecisionFailedEvent(pausedInBudget));
    }

    private GetAllSagasQuery all() {
        return filtered(null, null, null, null);
    }

    private GetAllSagasQuery filtered(UUID workOrderId, SagaState state, SagaStep step, SagaState pausedFrom) {
        return new GetAllSagasQuery(workOrderId, state, step, pausedFrom, null, null, null, null, 0, 20, "createdAt", "ASC");
    }

    private List<UUID> ids(PageResult<SagaInstance> page) {
        return page.content().stream().map(SagaInstance::getWorkOrderId).toList();
    }

    @Test
    @DisplayName("Deve listar todas as sagas quando nao houver filtro")
    void shouldListAll() {
        PageResult<SagaInstance> page = queryGateway.findAll(all());

        assertEquals(4, page.totalElements());
        assertEquals(List.of(open, awaitingBudget, awaitingDecision, pausedInBudget), ids(page));
    }

    @Test
    @DisplayName("Deve filtrar pelo id da OS, que e o id da saga")
    void shouldFilterByWorkOrderId() {
        assertEquals(List.of(awaitingBudget), ids(queryGateway.findAll(filtered(awaitingBudget, null, null, null))));
        assertEquals(0, queryGateway.findAll(filtered(UUID.randomUUID(), null, null, null)).totalElements());
    }

    @Test
    @DisplayName("Deve filtrar pela situacao e pelo estado de onde a saga foi pausada")
    void shouldFilterByStateAndPausedFrom() {
        assertEquals(List.of(awaitingDecision), ids(queryGateway.findAll(filtered(null, SagaState.AWAITING_CUSTOMER_DECISION, null, null))));
        assertEquals(List.of(pausedInBudget), ids(queryGateway.findAll(filtered(null, SagaState.PAUSED, null, null))));
        assertEquals(List.of(pausedInBudget), ids(queryGateway.findAll(filtered(null, null, null, SagaState.BUDGET_GENERATED))));
        assertEquals(0, queryGateway.findAll(filtered(null, null, null, SagaState.AWAITING_BUDGET)).totalElements());
    }

    @Test
    @DisplayName("Deve filtrar pela etapa, contando a saga pausada na etapa em que parou")
    void shouldFilterByStepIncludingPausedSagas() {
        assertEquals(List.of(open), ids(queryGateway.findAll(filtered(null, null, SagaStep.REGISTRATION, null))));
        assertEquals(List.of(awaitingBudget, pausedInBudget), ids(queryGateway.findAll(filtered(null, null, SagaStep.BUDGET, null))));
        assertEquals(List.of(awaitingDecision), ids(queryGateway.findAll(filtered(null, null, SagaStep.CUSTOMER_DECISION, null))));
        assertEquals(0, queryGateway.findAll(filtered(null, null, SagaStep.PAYMENT, null)).totalElements());
    }

    @Test
    @DisplayName("Deve combinar os filtros com E")
    void shouldCombineFilters() {
        assertEquals(List.of(pausedInBudget), ids(queryGateway.findAll(filtered(null, SagaState.PAUSED, SagaStep.BUDGET, null))));
        assertEquals(0, queryGateway.findAll(filtered(null, SagaState.PAUSED, SagaStep.REGISTRATION, null)).totalElements());
    }

    @Test
    @DisplayName("Deve filtrar pelo periodo de criacao e de atualizacao")
    void shouldFilterByDateRanges() {
        Instant past = Instant.now().minusSeconds(3600);
        Instant future = Instant.now().plusSeconds(3600);

        assertEquals(4, queryGateway.findAll(range(past, future, null, null)).totalElements());
        assertEquals(0, queryGateway.findAll(range(future, null, null, null)).totalElements());
        assertEquals(0, queryGateway.findAll(range(null, past, null, null)).totalElements());
        assertEquals(4, queryGateway.findAll(range(null, null, past, future)).totalElements());
        assertEquals(0, queryGateway.findAll(range(null, null, future, null)).totalElements());
    }

    private GetAllSagasQuery range(Instant createdFrom, Instant createdTo, Instant updatedFrom, Instant updatedTo) {
        return new GetAllSagasQuery(null, null, null, null, createdFrom, createdTo, updatedFrom, updatedTo, 0, 20, "createdAt", "ASC");
    }

    @Test
    @DisplayName("Deve paginar e ordenar, com a ordem decrescente por padrao de atualizacao")
    void shouldPaginateAndSort() {
        PageResult<SagaInstance> first = queryGateway.findAll(new GetAllSagasQuery(null, null, null, null, null, null, null, null, 0, 3, "createdAt", "ASC"));
        PageResult<SagaInstance> second = queryGateway.findAll(new GetAllSagasQuery(null, null, null, null, null, null, null, null, 1, 3, "createdAt", "ASC"));

        assertEquals(4, first.totalElements());
        assertEquals(2, first.totalPages());
        assertEquals(List.of(open, awaitingBudget, awaitingDecision), ids(first));
        assertEquals(List.of(pausedInBudget), ids(second));

        PageResult<SagaInstance> newestFirst = getAll.execute(new GetAllSagasQuery(null, null, null, null, null, null, null, null, 0, 20, null, null));
        assertEquals(pausedInBudget, newestFirst.content().get(0).getWorkOrderId());
        assertEquals(open, newestFirst.content().get(3).getWorkOrderId());
    }

    @Test
    @DisplayName("Deve respeitar a direcao ao ordenar pelo id da OS (crescente e decrescente)")
    void shouldHonorDirectionWhenSortingByWorkOrderId() {
        List<UUID> all = List.of(open, awaitingBudget, awaitingDecision, pausedInBudget);
        // O MongoDB ordena UUID byte a byte, sem sinal; a ordem do texto hexadecimal e a mesma (o UUID.compareTo do Java usa sinal).
        List<UUID> ascending = all.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        List<UUID> descending = ascending.reversed();

        PageResult<SagaInstance> asc = queryGateway.findAll(new GetAllSagasQuery(null, null, null, null, null, null, null, null, 0, 20, "workOrderId", "ASC"));
        PageResult<SagaInstance> desc = queryGateway.findAll(new GetAllSagasQuery(null, null, null, null, null, null, null, null, 0, 20, "workOrderId", "DESC"));

        assertEquals(ascending, ids(asc));
        assertEquals(descending, ids(desc));
    }
}
