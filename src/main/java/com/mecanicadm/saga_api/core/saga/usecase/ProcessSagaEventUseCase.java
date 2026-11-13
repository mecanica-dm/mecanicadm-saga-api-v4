package com.mecanicadm.saga_api.core.saga.usecase;

import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaGateway;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCreatedEvent;
import com.mecanicadm.saga_api.core.saga.exception.SagaExceptions;
import com.mecanicadm.saga_api.core.saga.engine.SagaOrchestrator;
import com.mecanicadm.saga_api.core.shared.messaging.MessagePublisher;
import com.mecanicadm.saga_api.shared.usecase.VoidUseCase;

import java.util.List;
import java.util.Optional;

public class ProcessSagaEventUseCase implements VoidUseCase<SagaEvent> {
    private final SagaGateway gateway;
    private final SagaOrchestrator orchestrator;
    private final MessagePublisher publisher;

    public ProcessSagaEventUseCase(SagaGateway gateway, SagaOrchestrator orchestrator, MessagePublisher publisher) {
        this.gateway = gateway;
        this.orchestrator = orchestrator;
        this.publisher = publisher;
    }

    @Override
    public void execute(SagaEvent event) {
        Optional<SagaInstance> existing = gateway.findById(event.workOrderId());
        SagaInstance saga;
        List<SagaCommand> commands;
        if (existing.isPresent()) {
            saga = existing.get();
            commands = orchestrator.handle(saga, event);
        } else if (event instanceof WorkOrderCreatedEvent created) {
            saga = orchestrator.start(created);
            commands = List.of();
        } else {
            throw new SagaExceptions.NotFound();
        }

        gateway.save(saga);
        commands.forEach(command -> publisher.publish(saga.getWorkOrderId(), command));
    }
}
