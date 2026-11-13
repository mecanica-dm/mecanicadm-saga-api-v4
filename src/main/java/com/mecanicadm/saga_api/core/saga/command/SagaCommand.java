package com.mecanicadm.saga_api.core.saga.command;

import java.util.UUID;

public sealed interface SagaCommand permits
        CancelWorkOrderIntegrationCommand,
        ReleaseWorkOrderVehicleIntegrationCommand,
        ChangesRequestedWorkOrderIntegrationCommand,
        AuthorizeWorkOrderIntegrationCommand,
        GenerateBudgetIntegrationCommand,
        CancelBudgetIntegrationCommand,
        GeneratePaymentIntegrationCommand,
        CancelPaymentIntegrationCommand,
        RefundPaymentIntegrationCommand {

    UUID workOrderId();
}
