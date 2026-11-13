package com.mecanicadm.saga_api.infra.features.saga.persistence;

import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.SagaTransition;
import com.mecanicadm.saga_api.core.saga.domain.WorkOrderSnapshot;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Document("saga_instances")
public class SagaDocument {

    @Id
    private final UUID workOrderId;
    @Version
    private final Long version;
    @Indexed
    private final SagaState state;
    private final SagaState pausedFrom;
    private final WorkOrderSnapshot workOrder;
    private final BigDecimal budgetTotal;
    private final List<String> pendingCompensations;
    private final String pauseReason;
    private final String paymentLink;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<SagaTransition> history;

    @SuppressWarnings("java:S107")
    public SagaDocument(UUID workOrderId, Long version, SagaState state, SagaState pausedFrom, WorkOrderSnapshot workOrder, BigDecimal budgetTotal,
                        List<String> pendingCompensations, String pauseReason, String paymentLink, Instant createdAt,
                        Instant updatedAt, List<SagaTransition> history) {
        this.workOrderId = workOrderId;
        this.version = version;
        this.state = state;
        this.pausedFrom = pausedFrom;
        this.workOrder = workOrder;
        this.budgetTotal = budgetTotal;
        this.pendingCompensations = pendingCompensations == null ? List.of() : pendingCompensations;
        this.pauseReason = pauseReason;
        this.paymentLink = paymentLink;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.history = history;
    }

    public UUID getWorkOrderId() {
        return workOrderId;
    }

    public Long getVersion() {
        return version;
    }

    public SagaState getState() {
        return state;
    }

    public SagaState getPausedFrom() {
        return pausedFrom;
    }

    public WorkOrderSnapshot getWorkOrder() {
        return workOrder;
    }

    public BigDecimal getBudgetTotal() {
        return budgetTotal;
    }

    public List<String> getPendingCompensations() {
        return pendingCompensations;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public String getPaymentLink() {
        return paymentLink;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<SagaTransition> getHistory() {
        return history;
    }
}
