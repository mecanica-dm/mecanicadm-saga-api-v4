package com.mecanicadm.saga_api.infra.features.saga.api.dto;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaTransition;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Saga com o histórico completo: um registro para cada evento recebido")
public record SagaResponse(
        @Schema(description = "Situação atual da saga") SagaSummaryResponse saga,
        @Schema(description = "Histórico em ordem cronológica: o evento recebido, o estado de origem e de destino e os commands enviados")
        List<TransitionResponse> history) {

    public static SagaResponse from(SagaInstance saga) {
        return new SagaResponse(SagaSummaryResponse.from(saga),
                saga.getHistory().stream().map(TransitionResponse::from).toList());
    }

    @Schema(description = "Uma transição da saga, causada por um evento recebido")
    public record TransitionResponse(
            @Schema(description = "Evento que a saga recebeu (por exemplo, BudgetApprovedEvent)") String eventType,
            @Schema(description = "Estado antes do evento; vazio na criação da saga", nullable = true) SagaState fromState,
            @Schema(description = "Estado depois do evento") SagaState toState,
            @Schema(description = "Commands que a saga enviou em resposta ao evento") List<String> commands,
            @Schema(description = "Quando o evento foi processado") java.time.Instant occurredAt) {

        static TransitionResponse from(SagaTransition transition) {
            return new TransitionResponse(transition.eventType(), transition.fromState(), transition.toState(),
                    transition.commands(), transition.occurredAt());
        }
    }
}
