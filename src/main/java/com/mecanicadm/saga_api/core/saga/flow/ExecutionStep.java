package com.mecanicadm.saga_api.core.saga.flow;

import com.mecanicadm.saga_api.core.saga.command.GeneratePaymentIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionFinishedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderExecutionStartedEvent;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.engine.TransitionRule;

import java.util.List;

import static com.mecanicadm.saga_api.core.saga.engine.Guards.inState;

public class ExecutionStep implements SagaFlowStep {

    @Override
    public List<TransitionRule<?>> rules() {
        return List.of(
                TransitionRule.on(WorkOrderExecutionStartedEvent.class, inState(SagaState.AWAITING_EXECUTION), this::startExecution),
                TransitionRule.on(WorkOrderExecutionFinishedEvent.class, inState(SagaState.IN_EXECUTION), this::requestPayment));
    }

    private List<SagaCommand> startExecution(SagaInstance saga, WorkOrderExecutionStartedEvent event) {
        saga.moveTo(SagaState.IN_EXECUTION);
        return List.of();
    }

    private List<SagaCommand> requestPayment(SagaInstance saga, WorkOrderExecutionFinishedEvent event) {
        saga.moveTo(SagaState.AWAITING_PAYMENT_LINK);
        return List.of(new GeneratePaymentIntegrationCommand(saga.getWorkOrderId()));
    }
}
