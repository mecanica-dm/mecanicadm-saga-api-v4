package com.mecanicadm.saga_api.core.saga.command;

import java.util.UUID;

public record ChangesRequestedWorkOrderIntegrationCommand(UUID workOrderId, String observation) implements SagaCommand {
}
