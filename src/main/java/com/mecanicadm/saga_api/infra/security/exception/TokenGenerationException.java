package com.mecanicadm.saga_api.infra.security.exception;

public class TokenGenerationException extends SecurityException {
    public TokenGenerationException(String messageKey, Throwable cause) {
        super(messageKey, cause);
    }
}
