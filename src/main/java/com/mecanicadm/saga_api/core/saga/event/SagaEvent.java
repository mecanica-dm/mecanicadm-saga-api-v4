package com.mecanicadm.saga_api.core.saga.event;

import java.util.UUID;

public sealed interface SagaEvent permits
        WorkOrderCreatedEvent,
        WorkOrderUpdatedEvent,
        WorkOrderDiagnosedEvent,
        WorkOrderExecutionStartedEvent,
        WorkOrderExecutionFinishedEvent,
        WorkOrderCanceledEvent,
        WorkOrderVehicleDeliveredEvent,
        WorkOrderCommandRejectedEvent,
        BudgetGeneratedEvent,
        BudgetUpdatedEvent,
        BudgetSentForDecisionEvent,
        BudgetSentForDecisionFailedEvent,
        BudgetApprovedEvent,
        BudgetRejectedEvent,
        BudgetChangesRequestedEvent,
        BudgetCanceledEvent,
        BudgetExpiredEvent,
        PaymentLinkGeneratedEvent,
        PaymentLinkGenerationFailedEvent,
        PaymentConfirmedEvent,
        PaymentFailedEvent,
        PaymentCanceledEvent,
        PaymentRefundedEvent,
        FinanceCommandRejectedEvent {

    UUID workOrderId();
}
