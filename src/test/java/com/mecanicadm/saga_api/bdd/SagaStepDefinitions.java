package com.mecanicadm.saga_api.bdd;

import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetExpiredEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetSentForDecisionEvent;
import com.mecanicadm.saga_api.core.saga.event.FinanceCommandRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentConfirmedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGeneratedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentLinkGenerationFailedEvent;
import com.mecanicadm.saga_api.core.saga.event.PaymentRefundedEvent;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCanceledEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCommandRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderDiagnosedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionFinishedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionStartedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderVehicleDeliveredEvent;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static com.mecanicadm.saga_api.testutils.SagaEvents.updated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SagaStepDefinitions {

    private static final Map<SagaState, Function<UUID, SagaEvent>> HAPPY_PATH = new LinkedHashMap<>();

    static {
        HAPPY_PATH.put(SagaState.AWAITING_BUDGET, WorkOrderDiagnosedEvent::new);
        HAPPY_PATH.put(SagaState.BUDGET_GENERATED, id -> new BudgetGeneratedEvent(id, new BigDecimal("720.00")));
        HAPPY_PATH.put(SagaState.AWAITING_CUSTOMER_DECISION, BudgetSentForDecisionEvent::new);
        HAPPY_PATH.put(SagaState.AWAITING_EXECUTION, BudgetApprovedEvent::new);
        HAPPY_PATH.put(SagaState.IN_EXECUTION, WorkOrderExecutionStartedEvent::new);
        HAPPY_PATH.put(SagaState.AWAITING_PAYMENT_LINK, WorkOrderExecutionFinishedEvent::new);
        HAPPY_PATH.put(SagaState.AWAITING_PAYMENT, id -> new PaymentLinkGeneratedEvent(id, "https://pagamento.test/abc"));
        HAPPY_PATH.put(SagaState.AWAITING_DELIVERY, PaymentConfirmedEvent::new);
        HAPPY_PATH.put(SagaState.COMPLETED, WorkOrderVehicleDeliveredEvent::new);
    }

    private final SagaWorld world = new SagaWorld();

    // ------------------------------------------------------------------ Dado

    @Dado("que uma OS foi aberta")
    public void umaOsFoiAberta() {
        world.receive(created(world.workOrderId()));
    }

    @Dado("que a saga da OS já chegou ao estado {string}")
    public void aSagaJaChegouAoEstado(String state) {
        SagaState target = SagaState.valueOf(state);
        assertTrue(HAPPY_PATH.containsKey(target), "Estado fora do caminho feliz: " + state);
        if (world.saga() == null) {
            world.receive(created(world.workOrderId()));
        }
        for (Map.Entry<SagaState, Function<UUID, SagaEvent>> step : HAPPY_PATH.entrySet()) {
            world.receive(step.getValue().apply(world.workOrderId()));
            if (step.getKey() == target) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ Quando

    @Quando("a Workorder publica o evento {string}")
    public void aWorkorderPublicaOEvento(String eventName) {
        world.receive(event(eventName));
    }

    @Quando("o Finance publica o evento {string}")
    public void oFinancePublicaOEvento(String eventName) {
        world.receive(event(eventName));
    }

    @Quando("a Workorder recusa o command {string}")
    public void aWorkorderRecusaOCommand(String command) {
        world.receive(new WorkOrderCommandRejectedEvent(world.workOrderId(), command, "InvalidStatusTransition", "Transicao invalida"));
    }

    @Quando("o Finance recusa o command {string}")
    public void oFinanceRecusaOCommand(String command) {
        world.receive(new FinanceCommandRejectedEvent(world.workOrderId(), command, "BudgetNotFound", "Orcamento nao encontrado"));
    }

    // ------------------------------------------------------------------ Entao

    @Entao("a saga está no estado {string}")
    public void aSagaEstaNoEstado(String state) {
        assertEquals(SagaState.valueOf(state), world.saga().getState());
    }

    @Entao("a saga enviou o command {string}")
    public void aSagaEnviouOCommand(String command) {
        assertTrue(world.commandsOfLastEvent().contains(command),
                "Esperava o command " + command + ", mas a saga enviou " + world.commandsOfLastEvent());
    }

    @Entao("a saga enviou os commands, nesta ordem:")
    public void aSagaEnviouOsCommandsNestaOrdem(DataTable table) {
        assertEquals(table.asList(), world.commandsOfLastEvent());
    }

    @Entao("a saga não enviou nenhum command")
    public void aSagaNaoEnviouNenhumCommand() {
        assertTrue(world.commandsOfLastEvent().isEmpty(), "A saga enviou " + world.commandsOfLastEvent());
    }

    @Entao("a saga guardou o total do orçamento de {string}")
    public void aSagaGuardouOTotalDoOrcamento(String total) {
        assertNotNull(world.saga().getBudgetTotal());
        assertEquals(0, new BigDecimal(total).compareTo(world.saga().getBudgetTotal()));
    }

    @Entao("a saga descartou o total do orçamento")
    public void aSagaDescartouOTotalDoOrcamento() {
        assertNull(world.saga().getBudgetTotal());
    }

    @Entao("a saga está pausada com o motivo contendo {string}")
    public void aSagaEstaPausadaComOMotivo(String reason) {
        assertNotNull(world.saga().getPauseReason());
        assertTrue(world.saga().getPauseReason().contains(reason), "Motivo da pausa: " + world.saga().getPauseReason());
    }

    @Entao("a saga aguarda a confirmação do command {string}")
    public void aSagaAguardaAConfirmacaoDoCommand(String command) {
        List<String> pending = world.saga().getPendingCompensations();
        assertTrue(pending.contains(command), "Compensações pendentes: " + pending);
    }

    @Entao("o histórico da saga registra {int} transições")
    public void oHistoricoRegistraTransicoes(int count) {
        assertEquals(count, world.saga().getHistory().size());
    }

    // ------------------------------------------------------------------ apoio

    private SagaEvent event(String name) {
        UUID id = world.workOrderId();
        return switch (name) {
            case "WorkOrderDiagnosedEvent" -> new WorkOrderDiagnosedEvent(id);
            case "WorkOrderExecutionStartedEvent" -> new WorkOrderExecutionStartedEvent(id);
            case "WorkOrderExecutionFinishedEvent" -> new WorkOrderExecutionFinishedEvent(id);
            case "WorkOrderVehicleDeliveredEvent" -> new WorkOrderVehicleDeliveredEvent(id);
            case "WorkOrderCanceledEvent" -> new WorkOrderCanceledEvent(id);
            case "WorkOrderUpdatedEvent" -> updated(id);
            case "BudgetGeneratedEvent" -> new BudgetGeneratedEvent(id, new BigDecimal("720.00"));
            case "BudgetSentForDecisionEvent" -> new BudgetSentForDecisionEvent(id);
            case "BudgetApprovedEvent" -> new BudgetApprovedEvent(id);
            case "BudgetRejectedEvent" -> new BudgetRejectedEvent(id);
            case "BudgetExpiredEvent" -> new BudgetExpiredEvent(id);
            case "BudgetCanceledEvent" -> new BudgetCanceledEvent(id);
            case "PaymentLinkGeneratedEvent" -> new PaymentLinkGeneratedEvent(id, "https://pagamento.test/abc");
            case "PaymentLinkGenerationFailedEvent" -> new PaymentLinkGenerationFailedEvent(id);
            case "PaymentConfirmedEvent" -> new PaymentConfirmedEvent(id);
            case "PaymentRefundedEvent" -> new PaymentRefundedEvent(id);
            default -> throw new IllegalArgumentException("Evento desconhecido nos cenários: " + name);
        };
    }
}
