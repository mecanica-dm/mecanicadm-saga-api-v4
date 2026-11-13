package com.mecanicadm.saga_api.infra.messaging.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mecanicadm.saga_api.core.saga.event.BudgetApprovedEvent;
import com.mecanicadm.saga_api.core.saga.event.BudgetChangesRequestedEvent;
import com.mecanicadm.saga_api.core.saga.event.FinanceCommandRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCommandRejectedEvent;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RabbitMQConfigTest {

    private final RabbitMQConfig config = new RabbitMQConfig();

    private Message message(String eventType, String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (eventType != null) {
            properties.setHeader("event-type", eventType);
        }
        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }

    @Test
    @DisplayName("Deve converter o payload do WorkOrderCreatedEvent com cliente, veiculo, mao de obra e materiais")
    void shouldConvertWorkOrderCreatedEventPayload() {
        String json = """
                {"client":{"id":"8b3f81e5-e118-4e31-8979-994cba5256e4","name":"Deliveries Express","email":"express@deliver.com",
                "phone":"48999999005","document":"11222333000181"},"status":"RECEIVED",
                "vehicle":{"brand":"Volvo","model":"Volvo FH 460","modelYear":2020,"licensePlate":"ABC-1234"},
                "laborItems":[{"id":"267ef2a5-0580-46ec-bae7-0dccc29c8156","name":"Limpeza de Carbono","price":300.00,
                "status":"AWAITING_EXECUTION","laborId":"e8281144-411a-4d37-97d8-1e4eb1e79391","executionEndAt":null,"executionStartAt":null}],
                "description":"teste","workOrderId":"a06ed083-93b2-4313-b9bc-fc3626b6b6b8",
                "materialItems":[{"id":"11e2c32c-5ae2-42e0-a51a-110183c6eb90","name":"Filtro de Oleo","type":"PART","brand":"Mann",
                "quantity":2,"unitPrice":45.00,"materialId":"5c5450e1-236b-43d9-95a7-96a1a1f73b15","totalPrice":90.00,"description":"Filtro"}],
                "executionEndAt":null,"executionStartAt":null}""";

        Object converted = config.eventMessageConverter().fromMessage(message("WorkOrderCreatedEvent", json));

        WorkOrderCreatedEvent event = assertInstanceOf(WorkOrderCreatedEvent.class, converted);
        assertEquals(UUID.fromString("a06ed083-93b2-4313-b9bc-fc3626b6b6b8"), event.workOrderId());
        assertEquals("Deliveries Express", event.client().name());
        assertEquals("ABC-1234", event.vehicle().licensePlate());
        assertEquals(1, event.laborItems().size());
        assertEquals(0, new BigDecimal("390.00").compareTo(event.snapshot().totalPrice()));
    }

    @Test
    @DisplayName("Deve ler o workOrderId dos eventos do Finance, ignorando os campos extras como messageId e ocurredAt")
    void shouldReadFinanceIdAsWorkOrderId() {
        UUID id = UUID.randomUUID();

        Object approved = config.eventMessageConverter().fromMessage(message("BudgetApprovedEvent", "{\"workOrderId\":\"" + id + "\"}"));
        Object changes = config.eventMessageConverter().fromMessage(message("BudgetChangesRequestedEvent",
                "{\"workOrderId\":\"" + id + "\",\"observation\":\"Remover item\"}"));

        assertEquals(id, assertInstanceOf(BudgetApprovedEvent.class, approved).workOrderId());
        BudgetChangesRequestedEvent event = assertInstanceOf(BudgetChangesRequestedEvent.class, changes);
        assertEquals(id, event.workOrderId());
        assertEquals("Remover item", event.observation());
    }

    @Test
    @DisplayName("Deve converter os eventos de recusa de command da Workorder e do Finance com command, excecao e mensagem")
    void shouldConvertCommandRejectedEvents() {
        UUID id = UUID.randomUUID();
        String json = "{\"workOrderId\":\"" + id + "\",\"eventType\":\"AuthorizeWorkOrderIntegrationCommand\","
                + "\"exception\":\"InvalidStatusTransition\",\"message\":\"Transicao invalida\"}";

        WorkOrderCommandRejectedEvent workOrder = assertInstanceOf(WorkOrderCommandRejectedEvent.class,
                config.eventMessageConverter().fromMessage(message("WorkOrderCommandRejectedEvent", json)));
        FinanceCommandRejectedEvent finance = assertInstanceOf(FinanceCommandRejectedEvent.class,
                config.eventMessageConverter().fromMessage(message("FinanceCommandRejectedEvent", json)));

        assertEquals(new WorkOrderCommandRejectedEvent(id, "AuthorizeWorkOrderIntegrationCommand", "InvalidStatusTransition", "Transicao invalida"), workOrder);
        assertEquals(new FinanceCommandRejectedEvent(id, "AuthorizeWorkOrderIntegrationCommand", "InvalidStatusTransition", "Transicao invalida"), finance);
    }

    @Test
    @DisplayName("Deve mapear todos os eventos da saga pelo nome da classe no header event-type")
    void shouldMapEveryPermittedEventByClassName() {
        UUID id = UUID.randomUUID();
        String json = "{\"workOrderId\":\"" + id + "\"}";

        Arrays.stream(SagaEvent.class.getPermittedSubclasses()).forEach(type -> {
            Object converted = config.eventMessageConverter().fromMessage(message(type.getSimpleName(), json));
            assertInstanceOf(type, converted);
            assertEquals(id, ((SagaEvent) converted).workOrderId());
        });
    }

    @Test
    @DisplayName("Deve recusar a conversao quando o event-type for desconhecido ou faltar")
    void shouldRefuseConversionWhenEventTypeIsUnknownOrMissing() {
        var converter = config.eventMessageConverter();
        Message unknownType = message("DesconhecidoEvent", "{}");
        Message missingType = message(null, "{}");

        assertThrows(MessageConversionException.class, () -> converter.fromMessage(unknownType));
        assertThrows(MessageConversionException.class, () -> converter.fromMessage(missingType));
    }

    @Test
    @DisplayName("Deve ter um bind na topologia para cada evento da saga")
    void shouldHaveBindingForEveryEventInTopology() throws IOException {
        String definitions = new ObjectMapper().readTree(new File("messaging/rabbitmq-definitions.json")).toString();

        Arrays.stream(SagaEvent.class.getPermittedSubclasses())
                .forEach(type -> assertTrue(definitions.contains("\"" + type.getSimpleName() + "\""), type.getSimpleName()));
    }
}
