package com.mecanicadm.saga_api.core.saga.domain;

import java.time.Instant;
import java.util.List;

public record SagaTransition(String eventType, SagaState fromState, SagaState toState, List<String> commands, Instant occurredAt) {
}
