package com.mecanicadm.saga_api.infra.handler;

import com.mecanicadm.saga_api.shared.exception.DomainExceptionCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Locale;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_FIELD = "error";

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(DomainExceptionCore.class)
    public ResponseEntity<Map<String, String>> handleDomainException(DomainExceptionCore ex, Locale locale) {
        String message = messageSource.getMessage(ex.getMessageKey(), ex.getArgs(), ex.getMessageKey(), locale);
        return ResponseEntity.status(HttpStatus.valueOf(ex.getStatus())).body(Map.of(ERROR_FIELD, message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleInvalidParameter(MethodArgumentTypeMismatchException ex, Locale locale) {
        String message = messageSource.getMessage("request.parameter.invalid", new Object[]{ex.getName(), ex.getValue()},
                "Valor invalido para o parametro " + ex.getName(), locale);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(ERROR_FIELD, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex, Locale locale) {
        LOGGER.error("Erro nao tratado", ex);
        String message = messageSource.getMessage("error.unexpected", null, "Ocorreu um erro inesperado.", locale);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(ERROR_FIELD, message));
    }
}
