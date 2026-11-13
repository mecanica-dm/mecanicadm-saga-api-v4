package com.mecanicadm.saga_api.infra.messaging.inbox;

import com.mecanicadm.saga_api.testutils.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
class InboxStoreIT extends AbstractIntegrationTest {

    @Autowired
    private InboxStore inbox;

    @Autowired
    private MongoTemplate mongoTemplate;

    private final UUID messageId = UUID.randomUUID();
    private final UUID workOrderId = UUID.randomUUID();

    @BeforeEach
    void cleanUp() {
        clearCollections(mongoTemplate);
    }

    @Test
    @DisplayName("Deve registrar a mensagem nova e tratar o reenvio de uma mensagem PROCESSED como duplicata")
    void shouldRegisterNewMessageAndRejectProcessedRedelivery() {
        int first = inbox.register(messageId, "WorkOrderCreatedEvent", workOrderId, "PROCESSED", Instant.now());
        int second = inbox.register(messageId, "WorkOrderCreatedEvent", workOrderId, "PROCESSED", Instant.now());

        assertEquals(1, first);
        assertEquals(0, second);
        assertEquals(InboxStatus.PROCESSED, inbox.findById(messageId).getStatus());
    }

    @Test
    @DisplayName("Deve registrar a falha com FAILED e o motivo")
    void shouldRegisterFailureWithReason() {
        inbox.registerFailure(messageId, "WorkOrderCreatedEvent", workOrderId, "banco fora", Instant.now());

        InboxDocument stored = inbox.findById(messageId);
        assertEquals(InboxStatus.FAILED, stored.getStatus());
        assertEquals("banco fora", stored.getError());
    }

    @Test
    @DisplayName("Deve permitir reprocessar uma mensagem FAILED reenviada e passar para PROCESSED, limpando o erro")
    void shouldReprocessFailedMessage() {
        inbox.registerFailure(messageId, "WorkOrderCreatedEvent", workOrderId, "banco fora", Instant.now());

        int reprocessed = inbox.register(messageId, "WorkOrderCreatedEvent", workOrderId, "PROCESSED", Instant.now());

        assertEquals(1, reprocessed);
        InboxDocument stored = inbox.findById(messageId);
        assertEquals(InboxStatus.PROCESSED, stored.getStatus());
        assertNull(stored.getError());
    }

    @Test
    @DisplayName("Deve permitir reprocessar uma mensagem REJECTED reenviada, inclusive com outra OS")
    void shouldReprocessRejectedMessage() {
        UUID otherWorkOrderId = UUID.randomUUID();
        inbox.register(messageId, "WorkOrderDiagnosedEvent", otherWorkOrderId, "REJECTED", Instant.now());

        int reprocessed = inbox.register(messageId, "WorkOrderDiagnosedEvent", workOrderId, "PROCESSED", Instant.now());

        assertEquals(1, reprocessed);
        InboxDocument stored = inbox.findById(messageId);
        assertEquals(InboxStatus.PROCESSED, stored.getStatus());
        assertEquals(workOrderId, stored.getWorkOrderId());
    }

    @Test
    @DisplayName("Nao deve sobrescrever uma mensagem PROCESSED com uma falha")
    void shouldNotOverwriteProcessedMessageWithFailure() {
        inbox.register(messageId, "WorkOrderCreatedEvent", workOrderId, "PROCESSED", Instant.now());

        int result = inbox.registerFailure(messageId, "WorkOrderCreatedEvent", workOrderId, "erro", Instant.now());

        assertEquals(0, result);
        assertEquals(InboxStatus.PROCESSED, inbox.findById(messageId).getStatus());
    }
}
