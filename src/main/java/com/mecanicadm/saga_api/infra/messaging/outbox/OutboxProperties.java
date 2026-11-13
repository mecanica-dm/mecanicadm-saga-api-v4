package com.mecanicadm.saga_api.infra.messaging.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "messaging.outbox")
public record OutboxProperties(
        @DefaultValue("50") int batchSize,
        @DefaultValue("5000") long confirmTimeoutMs,
        @DefaultValue("7") int retentionDays) {
}
