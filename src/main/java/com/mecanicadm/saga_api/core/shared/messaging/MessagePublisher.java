package com.mecanicadm.saga_api.core.shared.messaging;

import java.util.UUID;

public interface MessagePublisher {
    void publish(UUID workOrderId, Object message);
}
