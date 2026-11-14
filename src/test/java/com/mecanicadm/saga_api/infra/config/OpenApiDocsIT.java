package com.mecanicadm.saga_api.infra.config;

import com.mecanicadm.saga_api.testutils.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("O Swagger deve oferecer o botao Authorize com o esquema Bearer JWT e exigi-lo nos endpoints")
    void shouldOfferBearerAuthorization() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }

    @Test
    @DisplayName("Os endpoints devem ter resumo, descrição e respostas documentados")
    void shouldDocumentEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Mecânica DM Saga API"))
                .andExpect(jsonPath("$.paths['/sagas'].get.tags[0]").value("Sagas"))
                .andExpect(jsonPath("$.paths['/sagas'].get.summary").value("Listar as sagas"))
                .andExpect(jsonPath("$.paths['/sagas'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/sagas'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/sagas/{workOrderId}'].get.summary").value("Consultar uma saga e o seu histórico"))
                .andExpect(jsonPath("$.paths['/sagas/{workOrderId}'].get.responses['404']").exists());
    }

    @Test
    @DisplayName("Os parâmetros de filtro devem ter descrição")
    void shouldDescribeFilterParameters() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/sagas'].get.parameters[?(@.name=='state')].description").value("Situação atual da saga"))
                .andExpect(jsonPath("$.paths['/sagas'].get.parameters[?(@.name=='size')].description")
                        .value("Quantidade de itens por página, de 1 a 100"))
                .andExpect(jsonPath("$.paths['/sagas/{workOrderId}'].get.parameters[0].description").value("Id da ordem de serviço"));
    }
}
