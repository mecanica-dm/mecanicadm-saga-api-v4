package com.mecanicadm.saga_api.infra.security;

import com.mecanicadm.saga_api.testutils.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecuritySagaApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TokenService tokenService;

    private String bearer() {
        return "Bearer " + tokenService.generateToken("maria@teste.com");
    }

    @Test
    @DisplayName("A consulta das sagas deve exigir JWT")
    void shouldRequireJwtToQuerySagas() throws Exception {
        mockMvc.perform(get("/sagas")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/sagas/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token invalido ou de outro segredo deve ser recusado com 401")
    void shouldRefuseInvalidToken() throws Exception {
        mockMvc.perform(get("/sagas").header("Authorization", "Bearer token-invalido"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Com token valido da Workorder a consulta deve responder normalmente")
    void shouldAllowQueryWithValidToken() throws Exception {
        mockMvc.perform(get("/sagas").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(get("/sagas/{id}", UUID.randomUUID()).header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("O Swagger, o Actuator e o health devem continuar publicos")
    void shouldKeepDocsAndHealthPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
