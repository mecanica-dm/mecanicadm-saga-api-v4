package com.mecanicadm.saga_api.infra.security;

import com.mecanicadm.saga_api.infra.security.exception.InvalidTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityTokenTest {

    private final TokenService tokenService = new TokenService();
    private final SecurityFilter filter = new SecurityFilter(tokenService);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tokenService, "secret", "segredo-de-teste");
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Token gerado deve ser validado e devolver o assunto e a role")
    void shouldGenerateAndValidateToken() {
        String token = tokenService.generateToken("maria@teste.com");

        assertThat(tokenService.validateToken(token)).isEqualTo("maria@teste.com");
        TokenClaims claims = tokenService.decodeToken(token);
        assertThat(claims.subject()).isEqualTo("maria@teste.com");
        assertThat(claims.role()).isNull();
    }

    @Test
    @DisplayName("Token adulterado ou assinado com outro segredo deve ser recusado")
    void shouldRefuseInvalidToken() {
        String token = tokenService.generateToken("maria@teste.com");

        assertThatThrownBy(() -> tokenService.decodeToken(token + "x")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> tokenService.decodeToken("lixo")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("Filtro deve autenticar a requisicao com token Bearer valido")
    void shouldAuthenticateWithValidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokenService.generateToken("maria@teste.com"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("maria@teste.com");
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Filtro deve seguir sem autenticar quando nao houver cabecalho Authorization")
    void shouldContinueWithoutAuthorizationHeader() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Filtro deve responder 401 e interromper a cadeia quando o token for invalido")
    void shouldRespond401WhenTokenIsInvalid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalido");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Token inválido ou expirado");
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
