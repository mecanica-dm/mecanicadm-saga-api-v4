package com.mecanicadm.saga_api.core.saga.usecase;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaGateway;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaQueryGateway;
import com.mecanicadm.saga_api.core.saga.exception.SagaExceptions;
import com.mecanicadm.saga_api.core.saga.usecase.query.GetAllSagasQuery;
import com.mecanicadm.saga_api.core.saga.usecase.query.GetSagaByIdQuery;
import com.mecanicadm.saga_api.core.shared.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SagaQueryUseCasesTest {

    private final SagaQueryGateway queryGateway = mock(SagaQueryGateway.class);
    private final SagaGateway gateway = mock(SagaGateway.class);
    private final GetAllSagasUseCase getAll = new GetAllSagasUseCase(queryGateway);
    private final GetSagaByIdUseCase getById = new GetSagaByIdUseCase(gateway);

    private GetAllSagasQuery query(int page, int size, String sortBy, String direction) {
        return new GetAllSagasQuery(UUID.randomUUID(), SagaState.PAUSED, null, null, null, null, null, null, page, size, sortBy, direction);
    }

    private GetAllSagasQuery executeAndCapture(GetAllSagasQuery query) {
        when(queryGateway.findAll(any())).thenReturn(new PageResult<>(List.of(), 0, 20, 0));
        getAll.execute(query);
        ArgumentCaptor<GetAllSagasQuery> captor = ArgumentCaptor.forClass(GetAllSagasQuery.class);
        verify(queryGateway).findAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Deve repassar os filtros e normalizar pagina, tamanho e ordenacao invalidos")
    void shouldNormalizePagingAndSorting() {
        GetAllSagasQuery original = query(-3, 0, "senha", "sideways");

        GetAllSagasQuery sent = executeAndCapture(original);

        assertEquals(0, sent.page());
        assertEquals(GetAllSagasUseCase.DEFAULT_SIZE, sent.size());
        assertEquals("updatedAt", sent.sortBy());
        assertEquals("DESC", sent.direction());
        assertEquals(original.workOrderId(), sent.workOrderId());
        assertEquals(SagaState.PAUSED, sent.state());
    }

    @Test
    @DisplayName("Deve limitar o tamanho da pagina a 100 e aceitar ordenacao valida em qualquer caixa da direcao")
    void shouldCapPageSizeAndKeepValidSorting() {
        GetAllSagasQuery sent = executeAndCapture(query(2, 5000, "createdAt", "asc"));

        assertEquals(2, sent.page());
        assertEquals(GetAllSagasUseCase.MAX_SIZE, sent.size());
        assertEquals("createdAt", sent.sortBy());
        assertEquals("ASC", sent.direction());
    }

    @Test
    @DisplayName("Deve devolver a saga da OS e recusar quando nao existir")
    void shouldGetByIdOrFail() {
        UUID id = UUID.randomUUID();
        SagaInstance saga = SagaInstance.start(created(id));
        when(gateway.findById(id)).thenReturn(Optional.of(saga));
        when(gateway.findById(any(UUID.class))).thenAnswer(i -> id.equals(i.getArgument(0)) ? Optional.of(saga) : Optional.empty());

        assertSame(saga, getById.execute(new GetSagaByIdQuery(id)));
        assertThrows(SagaExceptions.NotFound.class, () -> getById.execute(new GetSagaByIdQuery(UUID.randomUUID())));
    }

    @Test
    @DisplayName("PageResult deve calcular o total de paginas e mapear o conteudo")
    void pageResultShouldComputeTotalPagesAndMap() {
        PageResult<Integer> page = new PageResult<>(List.of(1, 2), 0, 2, 5);

        assertEquals(3, page.totalPages());
        assertEquals(List.of("1", "2"), page.map(String::valueOf).content());
        assertEquals(0, new PageResult<Integer>(List.of(), 0, 0, 0).totalPages());
    }
}
