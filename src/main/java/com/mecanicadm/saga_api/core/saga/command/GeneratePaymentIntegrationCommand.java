package com.mecanicadm.saga_api.core.saga.command;

import java.util.UUID;

public record GeneratePaymentIntegrationCommand(UUID workOrderId) implements SagaCommand {
}
