package com.mecanicadm.saga_api.infra.features.saga.api.dto;

import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaStep;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Resumo do andamento de uma ordem de serviço no fluxo distribuído. Há uma saga por OS, com o mesmo id dela")
public record SagaSummaryResponse(
        @Schema(description = "Id da saga, que é o mesmo id da ordem de serviço") UUID workOrderId,
        @Schema(description = "Situação atual da saga (por exemplo, AWAITING_PAYMENT ou PAUSED)") SagaState state,
        @Schema(description = "Etapa atual do fluxo; uma saga pausada fica na etapa em que parou", nullable = true) SagaStep step,
        @Schema(description = "Estado em que a saga estava quando foi pausada", nullable = true) SagaState pausedFrom,
        @Schema(description = "Motivo da pausa, quando houver", nullable = true) String pauseReason,
        @Schema(description = "Valor total do orçamento, quando já foi gerado", nullable = true) BigDecimal budgetTotal,
        @Schema(description = "Link de pagamento gerado pelo Finance, quando houver", nullable = true) String paymentLink,
        @Schema(description = "Commands de compensação já enviados e ainda sem confirmação") List<String> pendingCompensations,
        @Schema(description = "Nome do cliente da ordem de serviço", nullable = true) String clientName,
        @Schema(description = "Placa do veículo da ordem de serviço", nullable = true) String licensePlate,
        @Schema(description = "Quando a saga foi criada") Instant createdAt,
        @Schema(description = "Quando a saga foi atualizada pela última vez") Instant updatedAt) {

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
