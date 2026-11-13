package com.mecanicadm.saga_api.core.saga.domain;

import com.mecanicadm.saga_api.core.saga.command.SagaCommand;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCreatedEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;

public class SagaInstance {

    private final UUID workOrderId;
    private final Long version;
    private final Instant createdAt;
    private final List<SagaTransition> history = new ArrayList<>();
    private SagaState state;
    private SagaState pausedFrom;
    private WorkOrderSnapshot workOrder;
    private BigDecimal budgetTotal;
    private final Set<String> pendingCompensations = new LinkedHashSet<>();
    private String pauseReason;
    private String paymentLink;
    private Instant updatedAt;

    private SagaInstance(UUID workOrderId, Long version, Instant createdAt) {
        this.workOrderId = workOrderId;
        this.version = version;
        this.createdAt = createdAt;
    }

    public static SagaInstance start(WorkOrderCreatedEvent event) {
        Instant now = Instant.now();
        SagaInstance saga = new SagaInstance(event.workOrderId(), null, now);
        saga.state = SagaState.WORK_ORDER_OPEN;
        saga.workOrder = event.snapshot();
        saga.updatedAt = now;
        saga.history.add(new SagaTransition(WorkOrderCreatedEvent.class.getSimpleName(), null, saga.state, List.of(), now));
        return saga;
    }

    @SuppressWarnings("java:S107")
    public static SagaInstance restore(UUID workOrderId, Long version, SagaState state, SagaState pausedFrom,
                                       WorkOrderSnapshot workOrder, BigDecimal budgetTotal, Collection<String> pendingCompensations,
                                       String pauseReason, String paymentLink, Instant createdAt, Instant updatedAt,
                                       List<SagaTransition> history) {
        SagaInstance saga = new SagaInstance(workOrderId, version, createdAt);
        saga.state = state;
        saga.pausedFrom = pausedFrom;
        saga.workOrder = workOrder;
        saga.budgetTotal = budgetTotal;
        saga.pendingCompensations.addAll(pendingCompensations);
        saga.pauseReason = pauseReason;
        saga.paymentLink = paymentLink;
        saga.updatedAt = updatedAt;
        saga.history.addAll(history);
        return saga;
    }

    public void moveTo(SagaState target) {
        state = target;
        pausedFrom = null;
        pauseReason = null;
    }

    public void pause(String reason) {
        pausedFrom = state;
        pauseReason = reason;
        state = SagaState.PAUSED;
    }

    public void replaceWorkOrder(WorkOrderSnapshot snapshot) {
        workOrder = snapshot;
    }

    public void registerBudget(BigDecimal total) {
        budgetTotal = total;
    }

    public void discardBudget() {
        budgetTotal = null;
    }

    public void expectCompensation(SagaCommand command) {
        pendingCompensations.add(command.getClass().getSimpleName());
    }

    public boolean confirmCompensation(Class<? extends SagaCommand> command) {
        return pendingCompensations.remove(command.getSimpleName());
    }

    public boolean isAwaitingCompensation(Class<? extends SagaCommand> command) {
        return pendingCompensations.contains(command.getSimpleName());
    }

    public boolean hasPendingCompensations() {
        return !pendingCompensations.isEmpty();
    }

    public void settleCancellation() {
        if ((isIn(SagaState.CANCELING) || isPausedFrom(SagaState.CANCELING)) && pendingCompensations.isEmpty()) {
            moveTo(SagaState.CANCELED);
        }
    }

    public void registerPaymentLink(String link) {
        paymentLink = link;
    }

    public void recordTransition(SagaEvent event, SagaState from, List<SagaCommand> commands) {
        updatedAt = Instant.now();
        history.add(new SagaTransition(event.getClass().getSimpleName(), from, state,
                commands.stream().map(command -> command.getClass().getSimpleName()).toList(), updatedAt));
    }

    public boolean isIn(SagaState... states) {
        return Arrays.asList(states).contains(state);
    }

    public boolean isPausedFrom(SagaState... states) {
        return state == SagaState.PAUSED && Arrays.asList(states).contains(pausedFrom);
    }

    public SagaStep currentStep() {
        return state == SagaState.PAUSED && pausedFrom != null ? pausedFrom.step() : state.step();
    }

    public boolean isInStep(SagaStep... steps) {
        return Arrays.asList(steps).contains(currentStep());
    }

    public boolean hasActiveBudget() {
        SagaStep step = currentStep();
        return step != null && step.isAtLeast(SagaStep.BUDGET);
    }

    public boolean hasPendingPayment() {
        SagaState[] states = {SagaState.AWAITING_PAYMENT_LINK, SagaState.AWAITING_PAYMENT};
        return isIn(states) || isPausedFrom(states);
    }

    public boolean hasConfirmedPayment() {
        return isIn(SagaState.AWAITING_DELIVERY) || isPausedFrom(SagaState.AWAITING_DELIVERY);
    }

    public boolean isTerminal() {
        return state.isTerminal();
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
        return List.copyOf(pendingCompensations);
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
        return List.copyOf(history);
    }
}
