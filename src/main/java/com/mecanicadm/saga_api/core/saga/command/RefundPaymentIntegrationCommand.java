package com.mecanicadm.saga_api.core.saga.command;

import java.util.UUID;

public record RefundPaymentIntegrationCommand(UUID workOrderId) implements SagaCommand {
}
