package com.mecanicadm.saga_api.core.saga.engine;

import com.mecanicadm.saga_api.core.saga.command.CancelBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelPaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.CancelWorkOrderIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.testutils.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompensationPlannerTest {

    private final UUID workOrderId = UUID.randomUUID();
    private final SagaInstance saga = SagaInstance.start(created(workOrderId));

    private static SagaFlowStep stepCompensating(Compensation... compensations) {
        List<Compensation> list = List.of(compensations);
        return new SagaFlowStep() {
            @Override
            public List<TransitionRule<?>> rules() {
                return List.of();
            }

            @Override
            public List<Compensation> compensations() {
                return list;
            }
        };
    }

    private final Compensation budget = Compensation.of(SagaStep.BUDGET, s -> true, s -> new CancelBudgetIntegrationCommand(workOrderId));
    private final Compensation payment = Compensation.of(SagaStep.PAYMENT, s -> true, s -> new CancelPaymentIntegrationCommand(workOrderId));
    private final Compensation workOrder = Compensation.of(SagaStep.REGISTRATION, s -> true, s -> new CancelWorkOrderIntegrationCommand(workOrderId));

    private CompensationPlanner plannerOf(SagaFlowStep... steps) {
        CompensationPlanner planner = new CompensationPlanner();
        planner.register(List.of(steps));
        return planner;
    }

    private List<Class<?>> types(List<SagaCommand> commands) {
        return commands.stream().<Class<?>>map(SagaCommand::getClass).toList();
    }

    @Test
    @DisplayName("Deve emitir as compensacoes da etapa mais avancada para a mais antiga, qualquer que seja a ordem de registro dos steps")
    void shouldCompensateFromTheMostAdvancedStepToTheOldest() {
        CompensationPlanner planner = plannerOf(stepCompensating(budget), stepCompensating(workOrder), stepCompensating(payment));

        List<SagaCommand> commands = planner.compensateFrom(SagaStep.REGISTRATION, saga, "teste");

        assertEquals(List.of(CancelPaymentIntegrationCommand.class, CancelBudgetIntegrationCommand.class,
                CancelWorkOrderIntegrationCommand.class), types(commands));
    }

    @Test
    @DisplayName("Deve juntar as compensacoes de varios steps e ignorar o step que nao tem nenhuma")
    void shouldGatherCompensationsOfSeveralStepsAndIgnoreStepsWithoutAny() {
        CompensationPlanner planner = plannerOf(stepCompensating(), stepCompensating(budget, payment), stepCompensating());

        assertEquals(2, planner.compensateFrom(SagaStep.REGISTRATION, saga, "teste").size());
    }

    @Test
    @DisplayName("Nao deve compensar etapas anteriores a etapa inicial pedida")
    void shouldNotCompensateStepsBeforeTheRequestedOne() {
        CompensationPlanner planner = plannerOf(stepCompensating(workOrder, budget, payment));

        assertEquals(List.of(CancelPaymentIntegrationCommand.class, CancelBudgetIntegrationCommand.class),
                types(planner.compensateFrom(SagaStep.BUDGET, saga, "teste")));
        assertEquals(List.of(CancelPaymentIntegrationCommand.class),
                types(planner.compensateFrom(SagaStep.PAYMENT, saga, "teste")));
    }

    @Test
    @DisplayName("Nao deve emitir a compensacao que nao se aplica ao estado da saga")
    void shouldSkipCompensationThatDoesNotApply() {
        Compensation never = Compensation.of(SagaStep.BUDGET, s -> false, s -> new CancelBudgetIntegrationCommand(workOrderId));
        CompensationPlanner planner = plannerOf(stepCompensating(never, payment));

        assertEquals(List.of(CancelPaymentIntegrationCommand.class), types(planner.compensateFrom(SagaStep.REGISTRATION, saga, "teste")));
    }

    @Test
    @DisplayName("Deve registrar SAGA_COMPENSATED com o motivo e os commands, e nada quando nao ha o que desfazer")
    void shouldLogOnlyWhenThereIsSomethingToUndo() {
        CompensationPlanner planner = plannerOf(stepCompensating(budget));
        try (LogCapture logs = LogCapture.of(CompensationPlanner.class)) {
            planner.compensateFrom(SagaStep.PAYMENT, saga, "nada");
            assertTrue(logs.lines().isEmpty());

            planner.compensateFrom(SagaStep.REGISTRATION, saga, "motivo X");

            String line = logs.single();
            assertTrue(line.startsWith("SAGA_COMPENSATED workOrderId=" + workOrderId), line);
            assertTrue(line.contains("reason=\"motivo X\"") && line.contains("[CancelBudgetIntegrationCommand]"), line);
        }
    }
}
