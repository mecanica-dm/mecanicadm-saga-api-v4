package com.mecanicadm.saga_api.shared.exception;

public abstract class DomainExceptionCore extends RuntimeException {
    private final transient Object[] args;
    private final int status;

    protected DomainExceptionCore(String messageKey, int status, Object... args) {
        super(messageKey);
        this.status = status;
        this.args = args;
    }

    public String getMessageKey() {
        return getMessage();
    }

    public Object[] getArgs() {
        return args;
    }

    public int getStatus() {
        return status;
    }
}

