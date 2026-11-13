package com.mecanicadm.saga_api.infra.features.saga.config;

import com.mecanicadm.saga_api.core.saga.flow.SagaFlow;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaGateway;
import com.mecanicadm.saga_api.core.saga.engine.SagaOrchestrator;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaQueryGateway;
import com.mecanicadm.saga_api.core.saga.usecase.GetAllSagasUseCase;
import com.mecanicadm.saga_api.core.saga.usecase.GetSagaByIdUseCase;
import com.mecanicadm.saga_api.core.saga.usecase.ProcessSagaEventUseCase;
import com.mecanicadm.saga_api.core.shared.messaging.MessagePublisher;
import com.mecanicadm.saga_api.infra.config.TransactionalProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class SagaConfiguration {

    @Bean
    public SagaOrchestrator sagaOrchestrator() {
        return SagaFlow.standard();
    }

    @Bean
    public ProcessSagaEventUseCase processSagaEventUseCase(SagaGateway gateway, SagaOrchestrator orchestrator, MessagePublisher publisher,
                                                           PlatformTransactionManager transactionManager) {
        return TransactionalProxy.of(new ProcessSagaEventUseCase(gateway, orchestrator, publisher), transactionManager);
    }

    @Bean
    public GetAllSagasUseCase getAllSagasUseCase(SagaQueryGateway gateway) {
        return new GetAllSagasUseCase(gateway);
    }

    @Bean
    public GetSagaByIdUseCase getSagaByIdUseCase(SagaGateway gateway) {
        return new GetSagaByIdUseCase(gateway);
    }
}
