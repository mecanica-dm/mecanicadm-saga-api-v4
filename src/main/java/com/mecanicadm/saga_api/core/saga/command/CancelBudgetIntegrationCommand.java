package com.mecanicadm.saga_api.core.saga.command;

import java.util.UUID;

public record CancelBudgetIntegrationCommand(UUID workOrderId) implements SagaCommand {
}
