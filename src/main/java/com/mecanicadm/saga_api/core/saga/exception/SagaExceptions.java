package com.mecanicadm.saga_api.core.saga.exception;

import com.mecanicadm.saga_api.shared.exception.DomainExceptionCore;

public final class SagaExceptions {

    private SagaExceptions() {
    }

    public static class NotFound extends DomainExceptionCore {
        public NotFound() {
            super("saga.not.found", 404);
        }
    }
}
