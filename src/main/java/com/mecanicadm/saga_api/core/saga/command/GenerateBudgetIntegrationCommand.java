package com.mecanicadm.saga_api.core.saga.command;

import com.mecanicadm.saga_api.core.saga.domain.WorkOrderSnapshot;

import java.util.UUID;

public record GenerateBudgetIntegrationCommand(UUID workOrderId, BudgetWorkOrderSnapshot workOrderSnapshot) implements SagaCommand {

    public static GenerateBudgetIntegrationCommand from(UUID workOrderId, WorkOrderSnapshot workOrder) {
        return new GenerateBudgetIntegrationCommand(workOrderId, BudgetWorkOrderSnapshot.from(workOrder));
    }
}
