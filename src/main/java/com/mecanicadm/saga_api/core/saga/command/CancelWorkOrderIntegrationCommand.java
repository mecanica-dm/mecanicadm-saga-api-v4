package com.mecanicadm.saga_api.core.saga.command;

import java.util.UUID;

public record CancelWorkOrderIntegrationCommand(UUID workOrderId) implements SagaCommand {
}
