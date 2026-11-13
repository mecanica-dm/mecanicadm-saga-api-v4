package com.mecanicadm.saga_api.infra.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Mecanica DM Saga API")
                .version("0.0.1")
                .description("Orquestrador da saga das ordens de servico. Esta API so consulta o andamento das sagas; "
                        + "o fluxo em si e conduzido por mensageria."));
    }
}
