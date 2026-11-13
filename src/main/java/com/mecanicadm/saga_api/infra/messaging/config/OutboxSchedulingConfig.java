package com.mecanicadm.saga_api.infra.messaging.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "messaging.outbox.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxSchedulingConfig {
}
