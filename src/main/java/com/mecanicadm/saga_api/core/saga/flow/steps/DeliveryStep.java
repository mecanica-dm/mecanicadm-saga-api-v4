package com.mecanicadm.saga_api.core.saga.flow.steps;

import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderVehicleDeliveredEvent;
import com.mecanicadm.saga_api.core.saga.engine.SagaFlowStep;
import com.mecanicadm.saga_api.core.saga.engine.TransitionRule;

import java.util.List;

import static com.mecanicadm.saga_api.core.saga.engine.Guards.inState;

public class DeliveryStep implements SagaFlowStep {

    @Override
    public List<TransitionRule<?>> rules() {
        return List.of(
                TransitionRule.on(WorkOrderVehicleDeliveredEvent.class, inState(SagaState.AWAITING_DELIVERY), this::complete));
    }

    private List<SagaCommand> complete(SagaInstance saga, WorkOrderVehicleDeliveredEvent event) {
        saga.moveTo(SagaState.COMPLETED);
        return List.of();
    }
}
