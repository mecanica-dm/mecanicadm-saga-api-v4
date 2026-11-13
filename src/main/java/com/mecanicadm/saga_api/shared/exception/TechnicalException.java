package com.mecanicadm.saga_api.shared.exception;

public class TechnicalException extends RuntimeException {

    private final String code;
    private final transient Object[] args;

    public TechnicalException(String code, Object... args) {
        super(code);
        this.code = code;
        this.args = args;
    }

    public String getCode() {
        return code;
    }

    public Object[] getArgs() {
        return args;
    }
}
