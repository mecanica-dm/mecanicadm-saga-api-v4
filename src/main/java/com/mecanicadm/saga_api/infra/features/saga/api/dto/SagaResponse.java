package com.mecanicadm.saga_api.infra.features.saga.api.dto;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaTransition;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Saga com o historico completo: um registro por evento recebido")
public record SagaResponse(
        SagaSummaryResponse saga,
        @Schema(description = "Historico em ordem cronologica: evento, estado de origem e de destino, commands enviados")
        List<TransitionResponse> history) {

    public static SagaResponse from(SagaInstance saga) {
        return new SagaResponse(SagaSummaryResponse.from(saga),
                saga.getHistory().stream().map(TransitionResponse::from).toList());
    }

    public record TransitionResponse(String eventType, SagaState fromState, SagaState toState, List<String> commands,
                                     java.time.Instant occurredAt) {

        static TransitionResponse from(SagaTransition transition) {
            return new TransitionResponse(transition.eventType(), transition.fromState(), transition.toState(),
                    transition.commands(), transition.occurredAt());
        }
    }
}
