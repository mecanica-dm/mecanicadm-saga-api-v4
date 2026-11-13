package com.mecanicadm.saga_api.infra.features.saga.amqp;

import com.mecanicadm.saga_api.infra.features.saga.persistence.SagaDocument;
import com.mecanicadm.saga_api.infra.messaging.inbox.InboxDocument;
import com.mecanicadm.saga_api.infra.messaging.inbox.InboxStatus;
import com.mecanicadm.saga_api.testutils.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = "messaging.outbox.scheduler.enabled=true")
class SagaMessagingIT extends AbstractIntegrationTest {

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @BeforeAll
    static void applyTopology() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + rabbit.getHost() + ":" + rabbit.getHttpPort() + "/api/definitions"))
                .header("content-type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (rabbit.getAdminUsername() + ":" + rabbit.getAdminPassword()).getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofFile(Path.of("messaging/rabbitmq-definitions.json")))
                .build();
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        AtomicReference<HttpResponse<String>> response = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
            response.set(client.send(request, HttpResponse.BodyHandlers.ofString()));
            return true;
        });
        assertTrue(response.get().statusCode() < 300, "Falha ao aplicar a topologia: " + response.get().body());
    }

    @DynamicPropertySource
    static void configureRabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MongoTemplate mongoTemplate;

    private final UUID workOrderId = UUID.randomUUID();

    @BeforeEach
    void prepare() {
        await().atMost(Duration.ofSeconds(30)).until(() -> amqpAdmin.getQueueProperties("saga.workorder-events") != null
                && amqpAdmin.getQueueProperties("saga.finance-events") != null);
        for (String queue : List.of("workorder", "budget", "saga.workorder-events.dlq", "saga.finance-events.dlq")) {
            rabbitTemplate.execute(channel -> channel.queuePurge(queue));
        }
        clearCollections(mongoTemplate);
    }

    private void send(String exchange, String eventType, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("event-type", eventType);
        properties.setHeader("message-id", UUID.randomUUID().toString());
        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).andProperties(properties).build();
        rabbitTemplate.send(exchange, "", message);
    }

    private String workOrderEvent() {
        return "{\"workOrderId\":\"" + workOrderId + "\"}";
    }

    private String financeEvent() {
        return "{\"workOrderId\":\"" + workOrderId + "\",\"messageId\":\"" + UUID.randomUUID() + "\",\"ocurredAt\":\"2026-11-08T12:00:00Z\"}";
    }

    private String createdJson() {
        return """
                {"client":{"id":"8b3f81e5-e118-4e31-8979-994cba5256e4","name":"Deliveries Express","email":"express@deliver.com",
                "phone":"48999999005","document":"11222333000181"},"status":"RECEIVED",
                "vehicle":{"brand":"Volvo","model":"Volvo FH 460","modelYear":2020,"licensePlate":"ABC-1234"},
                "laborItems":[{"id":"267ef2a5-0580-46ec-bae7-0dccc29c8156","name":"Limpeza de Carbono","price":300.00,
                "status":"AWAITING_EXECUTION","laborId":"e8281144-411a-4d37-97d8-1e4eb1e79391","executionEndAt":null,"executionStartAt":null}],
                "description":"teste","workOrderId":"%s",
                "materialItems":[{"id":"11e2c32c-5ae2-42e0-a51a-110183c6eb90","name":"Filtro de Oleo","type":"PART","brand":"Mann",
                "quantity":2,"unitPrice":45.00,"materialId":"5c5450e1-236b-43d9-95a7-96a1a1f73b15","totalPrice":90.00,"description":"Filtro"}],
                "executionEndAt":null,"executionStartAt":null}""".formatted(workOrderId);
    }

    private List<InboxStatus> inboxStatuses(Query query) {
        return mongoTemplate.find(query, InboxDocument.class).stream().map(InboxDocument::getStatus).toList();
    }

    private Message receiveOfType(String queue, String eventType) {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(queue, 1000);
            if (message != null && eventType.equals(message.getMessageProperties().getHeader("event-type"))) {
                return message;
            }
        }
        return null;
    }

    private String state() {
        SagaDocument document = mongoTemplate.findById(workOrderId, SagaDocument.class);
        return document == null ? null : document.getState().name();
    }

    @Test
    @DisplayName("Deve criar a saga ao receber WorkOrderCreatedEvent pela exchange workorder-output")
    void shouldCreateSagaWhenWorkOrderCreatedArrives() {
        send("workorder-output", "WorkOrderCreatedEvent", createdJson());

        await().atMost(Duration.ofSeconds(15)).until(() -> "WORK_ORDER_OPEN".equals(state()));
    }

    @Test
    @DisplayName("Deve entregar o GenerateBudgetIntegrationCommand na fila budget do Finance com o retrato da OS ao receber o diagnostico")
    void shouldDeliverGenerateBudgetToFinanceQueueWhenDiagnosed() {
        send("workorder-output", "WorkOrderCreatedEvent", createdJson());
        await().atMost(Duration.ofSeconds(15)).until(() -> "WORK_ORDER_OPEN".equals(state()));

        send("workorder-output", "WorkOrderDiagnosedEvent", workOrderEvent());

        Message command = receiveOfType("budget", "GenerateBudgetIntegrationCommand");
        assertNotNull(command);
        assertNotNull(command.getMessageProperties().getHeader("message-id"));
        String body = new String(command.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"workOrderSnapshot\":{"), body);
        assertTrue(body.contains("Deliveries Express"), body);
        assertTrue(body.contains("Limpeza de Carbono"), body);
        assertTrue(body.contains("Filtro de Oleo"), body);
        assertTrue(body.contains("\"totalPrice\":390"), body);
        assertFalse(body.contains("\"workOrder\":{"), body);
    }

    @Test
    @DisplayName("Deve entregar o AuthorizeWorkOrderIntegrationCommand na fila da Workorder quando o orcamento for aprovado")
    void shouldDeliverAuthorizeToWorkOrderQueueWhenBudgetApproved() {
        send("workorder-output", "WorkOrderCreatedEvent", createdJson());
        await().atMost(Duration.ofSeconds(15)).until(() -> "WORK_ORDER_OPEN".equals(state()));
        send("workorder-output", "WorkOrderDiagnosedEvent", workOrderEvent());
        await().atMost(Duration.ofSeconds(15)).until(() -> "AWAITING_BUDGET".equals(state()));
        send("finance-output", "BudgetGeneratedEvent", "{\"workOrderId\":\"" + workOrderId + "\",\"totalPrice\":390}");
        await().atMost(Duration.ofSeconds(15)).until(() -> "BUDGET_GENERATED".equals(state()));
        send("finance-output", "BudgetSentForDecisionEvent", financeEvent());
        await().atMost(Duration.ofSeconds(15)).until(() -> "AWAITING_CUSTOMER_DECISION".equals(state()));

        send("finance-output", "BudgetApprovedEvent", financeEvent());

        Message command = receiveOfType("workorder", "AuthorizeWorkOrderIntegrationCommand");
        assertNotNull(command);
        String body = new String(command.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"workOrderId\"") && body.contains(workOrderId.toString()));
        await().atMost(Duration.ofSeconds(15)).until(() -> "AWAITING_EXECUTION".equals(state()));
    }

    @Test
    @DisplayName("Deve registrar REJECTED no inbox, sem DLQ, quando chegar evento de uma OS sem saga")
    void shouldRegisterRejectedWhenEventArrivesForUnknownSaga() {
        send("workorder-output", "WorkOrderDiagnosedEvent", workOrderEvent());

        await().atMost(Duration.ofSeconds(15)).until(() -> inboxStatuses(Query.query(Criteria.where("workOrderId").is(workOrderId)))
                .equals(List.of(InboxStatus.REJECTED)));
        assertEquals(null, rabbitTemplate.receive("saga.workorder-events.dlq", 1000));
    }

    @Test
    @DisplayName("Deve reprocessar o mesmo messageId recusado quando a saga passar a existir")
    void shouldReprocessRejectedMessageAfterSagaIsCreated() {
        UUID messageId = UUID.randomUUID();
        sendWithMessageId("workorder-output", "WorkOrderDiagnosedEvent", workOrderEvent(), messageId);
        await().atMost(Duration.ofSeconds(15)).until(() -> inboxStatuses(Query.query(Criteria.where("_id").is(messageId)))
                .equals(List.of(InboxStatus.REJECTED)));
        send("workorder-output", "WorkOrderCreatedEvent", createdJson());
        await().atMost(Duration.ofSeconds(15)).until(() -> "WORK_ORDER_OPEN".equals(state()));

        sendWithMessageId("workorder-output", "WorkOrderDiagnosedEvent", workOrderEvent(), messageId);

        await().atMost(Duration.ofSeconds(15)).until(() -> "AWAITING_BUDGET".equals(state()));
        assertEquals(List.of(InboxStatus.PROCESSED), inboxStatuses(Query.query(Criteria.where("_id").is(messageId))));
    }

    @Test
    @DisplayName("Deve mandar para a DLQ a mensagem com event-type desconhecido")
    void shouldSendToDeadLetterQueueWhenEventTypeIsUnknown() {
        send("workorder-output", "WorkOrderCreatedEvent", "isto nao e json");

        assertNotNull(rabbitTemplate.receive("saga.workorder-events.dlq", 15000));
    }

    private void sendWithMessageId(String exchange, String eventType, String body, UUID messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("event-type", eventType);
        properties.setHeader("message-id", messageId.toString());
        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).andProperties(properties).build();
        rabbitTemplate.send(exchange, "", message);
    }
}
