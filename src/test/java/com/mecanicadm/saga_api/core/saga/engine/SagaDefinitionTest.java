package com.mecanicadm.saga_api.core.saga.engine;

import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.GenerateBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderDiagnosedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SagaDefinitionTest {

    private final UUID workOrderId = UUID.randomUUID();
    private final SagaInstance saga = SagaInstance.start(created(workOrderId));

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SagaFlowStep coverEveryEvent() {
        List<TransitionRule<?>> rules = new ArrayList<>();
        for (Class<?> type : SagaEvent.class.getPermittedSubclasses()) {
            rules.add(TransitionRule.on((Class) type, Guards.always(), (s, e) -> List.of()));
        }
        return () -> rules;
    }

    private static SagaFlowStep stepWith(TransitionRule<?>... rules) {
        List<TransitionRule<?>> list = List.of(rules);
        return () -> list;
    }

    private List<SagaCommand> commandOf(SagaDefinition definition, SagaEvent event, boolean terminal) {
        return (terminal ? definition.terminalRuleFor(event, saga) : definition.ruleFor(event, saga))
                .map(rule -> rule.apply(saga, event))
                .orElse(List.of());
    }

    @Test
    @DisplayName("Deve montar quando todo evento da saga tem regra")
    void shouldAssembleWhenEveryEventHasARule() {
        assertDoesNotThrow(() -> new SagaDefinition(List.of(coverEveryEvent())));
    }

    @Test
    @DisplayName("Deve recusar a montagem quando faltar regra para algum evento, listando os eventos descobertos")
    void shouldRefuseAssemblyWhenAnEventHasNoRule() {
        SagaFlowStep onlyOne = stepWith(TransitionRule.on(WorkOrderDiagnosedEvent.class, Guards.always(), (s, e) -> List.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new SagaDefinition(List.of(onlyOne)));

        assertTrue(error.getMessage().contains("BudgetApprovedEvent"));
        assertTrue(error.getMessage().contains("PaymentConfirmedEvent"));
        assertTrue(!error.getMessage().contains("WorkOrderDiagnosedEvent"));
    }

    @Test
    @DisplayName("Deve usar a primeira regra cuja guarda passa, na ordem em que os steps foram montados")
    void shouldUseTheFirstMatchingRuleInStepOrder() {
        SagaFlowStep first = stepWith(TransitionRule.on(WorkOrderDiagnosedEvent.class, Guards.inState(SagaState.AWAITING_BUDGET),
                (s, e) -> List.of(new CancelBudgetIntegrationCommand(workOrderId))));
        SagaFlowStep second = stepWith(TransitionRule.on(WorkOrderDiagnosedEvent.class, Guards.always(),
                (s, e) -> List.of(GenerateBudgetIntegrationCommand.from(workOrderId, s.getWorkOrder()))));
        SagaDefinition definition = new SagaDefinition(List.of(first, second, coverEveryEvent()));
        WorkOrderDiagnosedEvent event = new WorkOrderDiagnosedEvent(workOrderId);

        assertEquals(GenerateBudgetIntegrationCommand.class, commandOf(definition, event, false).get(0).getClass());
        saga.moveTo(SagaState.AWAITING_BUDGET);
        assertEquals(CancelBudgetIntegrationCommand.class, commandOf(definition, event, false).get(0).getClass());
    }

    @Test
    @DisplayName("Com a saga encerrada, so deve valer a regra marcada para depois do encerramento")
    void shouldOnlyUseAfterTerminationRulesForFinishedSagas() {
        SagaFlowStep step = stepWith(
                TransitionRule.on(WorkOrderDiagnosedEvent.class, Guards.always(),
                        (s, e) -> List.of(new CancelBudgetIntegrationCommand(workOrderId))),
                TransitionRule.afterTermination(WorkOrderDiagnosedEvent.class, Guards.always(),
                        (s, e) -> List.of(GenerateBudgetIntegrationCommand.from(workOrderId, s.getWorkOrder()))));
        SagaDefinition definition = new SagaDefinition(List.of(step, coverEveryEvent()));
        WorkOrderDiagnosedEvent event = new WorkOrderDiagnosedEvent(workOrderId);

        assertEquals(CancelBudgetIntegrationCommand.class, commandOf(definition, event, false).get(0).getClass());
        assertEquals(GenerateBudgetIntegrationCommand.class, commandOf(definition, event, true).get(0).getClass());
    }

    @Test
    @DisplayName("Com a saga encerrada, nao deve haver regra quando nenhuma esta marcada para depois do encerramento")
    void shouldHaveNoRuleForFinishedSagaWhenNoneIsAfterTermination() {
        SagaDefinition definition = new SagaDefinition(List.of(coverEveryEvent()));

        assertTrue(definition.terminalRuleFor(new WorkOrderDiagnosedEvent(workOrderId), saga).isEmpty());
    }
}
