package com.mecanicadm.saga_api.core.saga.command;

import java.util.UUID;

public record AuthorizeWorkOrderIntegrationCommand(UUID workOrderId) implements SagaCommand {
}
