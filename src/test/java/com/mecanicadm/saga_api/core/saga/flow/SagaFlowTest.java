package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.engine.Compensation;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SagaFlowTest {

    @Test
    @DisplayName("Deve montar o fluxo padrao com regra para todos os eventos da saga")
    void shouldAssembleStandardFlowCoveringEveryEvent() {
        assertDoesNotThrow(SagaFlow::standard);
    }

    private List<SagaStep> compensatedSteps(SagaFlowStep step) {
        return step.compensations().stream().map(Compensation::step).toList();
    }

    @Test
    @DisplayName("Cada step que produz efeito em outro servico deve declarar a sua compensacao")
    void shouldDeclareCompensationInsideEachStepThatHasAnEffect() {
        assertEquals(List.of(SagaStep.REGISTRATION), compensatedSteps(new RegistrationStep(new com.mecanicadm.saga_api.core.saga.engine.CompensationPlanner())));
        assertEquals(List.of(SagaStep.BUDGET), compensatedSteps(new BudgetStep()));
        assertEquals(List.of(SagaStep.PAYMENT, SagaStep.DELIVERY), compensatedSteps(new PaymentStep()));
    }

    @Test
    @DisplayName("Os steps que so reagem a eventos nao devem declarar compensacao")
    void shouldNotDeclareCompensationInStepsWithoutEffect() {
        assertTrue(new CustomerDecisionStep().compensations().isEmpty());
        assertTrue(new ExecutionStep().compensations().isEmpty());
        assertTrue(new DeliveryStep().compensations().isEmpty());
        assertTrue(new UnexpectedEventStep().compensations().isEmpty());
    }
}
