package com.mecanicadm.saga_api.infra.features.saga.api;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import com.mecanicadm.saga_api.core.saga.exception.SagaExceptions;
import com.mecanicadm.saga_api.core.saga.usecase.GetAllSagasUseCase;
import com.mecanicadm.saga_api.core.saga.usecase.GetSagaByIdUseCase;
import com.mecanicadm.saga_api.core.saga.usecase.query.GetAllSagasQuery;
import com.mecanicadm.saga_api.core.shared.PageResult;
import com.mecanicadm.saga_api.infra.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SagaControllerTest {

    private final GetAllSagasUseCase getAll = mock(GetAllSagasUseCase.class);
    private final GetSagaByIdUseCase getById = mock(GetSagaByIdUseCase.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("saga.not.found", java.util.Locale.ENGLISH, "Saga nao encontrada");
        messages.setUseCodeAsDefaultMessage(true);
        mockMvc = MockMvcBuilders.standaloneSetup(new SagaController(getAll, getById))
                .setControllerAdvice(new GlobalExceptionHandler(messages)).build();
    }

    @Test
    @DisplayName("Deve listar as sagas repassando todos os filtros e devolver a pagina com o resumo")
    void shouldListSagasWithFilters() throws Exception {
        UUID id = UUID.randomUUID();
        SagaInstance saga = SagaInstance.start(created(id));
        when(getAll.execute(any())).thenReturn(new PageResult<>(List.of(saga), 1, 10, 31));

        mockMvc.perform(get("/sagas").param("workOrderId", id.toString()).param("state", "WORK_ORDER_OPEN")
                        .param("step", "REGISTRATION").param("pausedFrom", "AWAITING_BUDGET")
                        .param("createdFrom", "2026-11-01T00:00:00Z").param("updatedTo", "2026-11-30T23:59:59Z")
                        .param("page", "1").param("size", "10").param("sortBy", "createdAt").param("direction", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].workOrderId").value(id.toString()))
                .andExpect(jsonPath("$.content[0].state").value("WORK_ORDER_OPEN"))
                .andExpect(jsonPath("$.content[0].step").value("REGISTRATION"))
                .andExpect(jsonPath("$.content[0].clientName").value("Deliveries Express"))
                .andExpect(jsonPath("$.content[0].licensePlate").value("ABC-1234"))
                .andExpect(jsonPath("$.totalElements").value(31))
                .andExpect(jsonPath("$.totalPages").value(4));

        ArgumentCaptor<GetAllSagasQuery> captor = ArgumentCaptor.forClass(GetAllSagasQuery.class);
        verify(getAll).execute(captor.capture());
        GetAllSagasQuery query = captor.getValue();
        assertEquals(id, query.workOrderId());
        assertEquals(SagaState.WORK_ORDER_OPEN, query.state());
        assertEquals(SagaStep.REGISTRATION, query.step());
        assertEquals(SagaState.AWAITING_BUDGET, query.pausedFrom());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), query.createdFrom());
        assertEquals(Instant.parse("2026-11-30T23:59:59Z"), query.updatedTo());
        assertEquals(1, query.page());
        assertEquals(10, query.size());
        assertEquals("createdAt", query.sortBy());
    }

    @Test
    @DisplayName("Deve usar pagina 0 e tamanho 20 quando nao informados")
    void shouldUseDefaultPaging() throws Exception {
        when(getAll.execute(any())).thenReturn(new PageResult<>(List.of(), 0, 20, 0));

        mockMvc.perform(get("/sagas")).andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));

        ArgumentCaptor<GetAllSagasQuery> captor = ArgumentCaptor.forClass(GetAllSagasQuery.class);
        verify(getAll).execute(captor.capture());
        assertEquals(0, captor.getValue().page());
        assertEquals(20, captor.getValue().size());
    }

    @Test
    @DisplayName("Deve responder 400 quando o estado ou a etapa forem invalidos")
    void shouldRespond400ForInvalidEnum() throws Exception {
        mockMvc.perform(get("/sagas").param("state", "NAO_EXISTE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(get("/sagas").param("step", "NAO_EXISTE")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Deve consultar a saga pelo id da OS com o historico")
    void shouldGetSagaWithHistory() throws Exception {
        UUID id = UUID.randomUUID();
        when(getById.execute(any())).thenReturn(SagaInstance.start(created(id)));

        mockMvc.perform(get("/sagas/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saga.workOrderId").value(id.toString()))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].eventType").value("WorkOrderCreatedEvent"))
                .andExpect(jsonPath("$.history[0].toState").value("WORK_ORDER_OPEN"));
    }

    @Test
    @DisplayName("Deve responder 404 quando a saga nao existir")
    void shouldRespond404WhenSagaDoesNotExist() throws Exception {
        when(getById.execute(any())).thenThrow(new SagaExceptions.NotFound());

        mockMvc.perform(get("/sagas/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Saga nao encontrada"));
    }

    @Test
    @DisplayName("Deve responder 500 com mensagem generica para erro inesperado")
    void shouldRespond500ForUnexpectedError() throws Exception {
        when(getById.execute(any())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(get("/sagas/{id}", UUID.randomUUID())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }
}
