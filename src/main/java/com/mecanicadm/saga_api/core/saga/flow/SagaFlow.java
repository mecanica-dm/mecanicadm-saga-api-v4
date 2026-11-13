package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.engine.CompensationPlanner;
import com.mecanicadm.saga_api.core.saga.engine.SagaDefinition;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.engine.SagaOrchestrator;

import java.util.List;

public final class SagaFlow {

    private SagaFlow() {
    }

    public static SagaOrchestrator standard() {
        CompensationPlanner planner = new CompensationPlanner();
        List<SagaFlowStep> steps = List.of(
                new RegistrationStep(planner),
                new BudgetStep(),
                new CustomerDecisionStep(),
                new ExecutionStep(),
                new PaymentStep(),
                new DeliveryStep(),
                new CancellationStep(planner),
                new CommandRejectionStep(planner),
                new UnexpectedEventStep());
        planner.register(steps);
        return new SagaOrchestrator(new SagaDefinition(steps));
    }
}
