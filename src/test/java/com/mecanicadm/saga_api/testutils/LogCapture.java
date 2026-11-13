package com.mecanicadm.saga_api.testutils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> type) {
        logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> type) {
        return new LogCapture(type);
    }

    public List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    public String single() {
        assertEquals(1, lines().size(), () -> "Logs capturados: " + lines());
        return lines().get(0);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
