package com.mecanicadm.saga_api.bdd;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaGateway;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.core.saga.flow.SagaFlow;
import com.mecanicadm.saga_api.core.saga.usecase.ProcessSagaEventUseCase;
import com.mecanicadm.saga_api.core.shared.messaging.MessagePublisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SagaWorld {

    private final Map<UUID, SagaInstance> sagas = new HashMap<>();
    private final List<String> commandsOfLastEvent = new ArrayList<>();
    private final ProcessSagaEventUseCase useCase;
    private final UUID workOrderId = UUID.randomUUID();

    public SagaWorld() {
        SagaGateway gateway = new SagaGateway() {
            @Override
            public Optional<SagaInstance> findById(UUID id) {
                return Optional.ofNullable(sagas.get(id));
            }

            @Override
            public SagaInstance save(SagaInstance saga) {
                sagas.put(saga.getWorkOrderId(), saga);
                return saga;
            }
        };
        MessagePublisher publisher = (id, message) -> commandsOfLastEvent.add(message.getClass().getSimpleName());
        this.useCase = new ProcessSagaEventUseCase(gateway, SagaFlow.standard(), publisher);
    }

    public UUID workOrderId() {
        return workOrderId;
    }

    public void receive(SagaEvent event) {
        commandsOfLastEvent.clear();
        useCase.execute(event);
    }

    public SagaInstance saga() {
        return sagas.get(workOrderId);
    }

    public List<String> commandsOfLastEvent() {
        return List.copyOf(commandsOfLastEvent);
    }
}
