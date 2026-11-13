package com.mecanicadm.saga_api.infra.features.saga.api.dto;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Resumo de uma saga. Ha uma saga por OS, identificada pelo id da OS.")
public record SagaSummaryResponse(
        @Schema(description = "Id da saga, que e o id da OS") UUID workOrderId,
        @Schema(description = "Situacao atual da saga") SagaState state,
        @Schema(description = "Etapa atual; uma saga pausada fica na etapa em que parou", nullable = true) SagaStep step,
        @Schema(description = "Estado de onde a saga foi pausada", nullable = true) SagaState pausedFrom,
        @Schema(description = "Motivo da pausa", nullable = true) String pauseReason,
        @Schema(description = "Total do orcamento", nullable = true) BigDecimal budgetTotal,
        @Schema(description = "Link de pagamento", nullable = true) String paymentLink,
        @Schema(description = "Compensacoes enviadas e ainda sem confirmacao") List<String> pendingCompensations,
        @Schema(description = "Nome do cliente da OS", nullable = true) String clientName,
        @Schema(description = "Placa do veiculo da OS", nullable = true) String licensePlate,
        Instant createdAt,
        Instant updatedAt) {

    public static SagaSummaryResponse from(SagaInstance saga) {
        return new SagaSummaryResponse(
                saga.getWorkOrderId(),
                saga.getState(),
                saga.currentStep(),
                saga.getPausedFrom(),
                saga.getPauseReason(),
                saga.getBudgetTotal(),
                saga.getPaymentLink(),
                saga.getPendingCompensations() == null ? List.of() : saga.getPendingCompensations(),
                saga.getWorkOrder() == null || saga.getWorkOrder().client() == null ? null : saga.getWorkOrder().client().name(),
                saga.getWorkOrder() == null || saga.getWorkOrder().vehicle() == null ? null : saga.getWorkOrder().vehicle().licensePlate(),
                saga.getCreatedAt(),
                saga.getUpdatedAt());
    }
}
