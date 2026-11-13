package com.mecanicadm.saga_api.core.saga.usecase;

import com.mecanicadm.saga_api.core.saga.flow.SagaFlow;
import com.mecanicadm.saga_api.core.saga.command.GenerateBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaGateway;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderDiagnosedEvent;
import com.mecanicadm.saga_api.core.saga.exception.SagaExceptions;
import com.mecanicadm.saga_api.core.shared.messaging.MessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessSagaEventUseCaseTest {

    @Mock
    private SagaGateway gateway;
    @Mock
    private MessagePublisher publisher;

    private ProcessSagaEventUseCase useCase;
    private final UUID workOrderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new ProcessSagaEventUseCase(gateway, SagaFlow.standard(), publisher);
    }

    @Test
    @DisplayName("Deve criar e salvar a saga ao receber WorkOrderCreatedEvent de uma OS desconhecida, sem publicar command")
    void shouldCreateSagaWhenWorkOrderCreatedForUnknownWorkOrder() {
        when(gateway.findById(workOrderId)).thenReturn(Optional.empty());

        useCase.execute(created(workOrderId));

        ArgumentCaptor<SagaInstance> captor = ArgumentCaptor.forClass(SagaInstance.class);
        verify(gateway).save(captor.capture());
        assertEquals(SagaState.WORK_ORDER_OPEN, captor.getValue().getState());
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("Deve recusar com SagaNotFound o evento de uma OS sem saga que nao seja WorkOrderCreatedEvent")
    void shouldRejectEventForUnknownSagaWhenNotCreated() {
        when(gateway.findById(workOrderId)).thenReturn(Optional.empty());

        assertThrows(SagaExceptions.NotFound.class, () -> useCase.execute(new WorkOrderDiagnosedEvent(workOrderId)));

        verify(gateway, never()).save(any());
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("Deve salvar a saga e publicar o command decidido, com o id da OS")
    void shouldSaveSagaAndPublishDecidedCommand() {
        when(gateway.findById(workOrderId)).thenReturn(Optional.of(SagaInstance.start(created(workOrderId))));

        useCase.execute(new WorkOrderDiagnosedEvent(workOrderId));

        verify(gateway).save(any(SagaInstance.class));
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(workOrderId), captor.capture());
        assertInstanceOf(GenerateBudgetIntegrationCommand.class, captor.getValue());
    }

    @Test
    @DisplayName("Deve salvar a saga sem publicar nada quando o evento nao gera command")
    void shouldSaveSagaWithoutPublishingWhenNoCommand() {
        when(gateway.findById(workOrderId)).thenReturn(Optional.of(SagaInstance.start(created(workOrderId))));

        useCase.execute(created(workOrderId));

        verify(gateway).save(any(SagaInstance.class));
        verifyNoInteractions(publisher);
    }
}
