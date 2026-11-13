package com.mecanicadm.saga_api.infra.messaging.serialization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mecanicadm.saga_api.core.saga.command.GenerateBudgetIntegrationCommand;
import com.mecanicadm.saga_api.core.saga.event.WorkOrderCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.mecanicadm.saga_api.testutils.SagaEvents.created;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GenerateBudgetCommandSerializationTest {

    private final ObjectMapper mapper = MessageJsonSerializer.newObjectMapper();
    private final MessageJsonSerializer serializer = new MessageJsonSerializer();
    private final UUID workOrderId = UUID.randomUUID();

    private Set<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return new TreeSet<>(names);
    }

    @Test
    @DisplayName("Deve serializar o command com workOrderId e workOrderSnapshot, com os campos do contrato do Finance")
    void shouldSerializeWithFinanceContract() throws Exception {
        WorkOrderCreatedEvent event = created(workOrderId);

        JsonNode json = mapper.readTree(serializer.toJson(GenerateBudgetIntegrationCommand.from(workOrderId, event.snapshot())));

        assertEquals(Set.of("workOrderId", "workOrderSnapshot"), fieldNames(json));
        assertEquals(workOrderId.toString(), json.get("workOrderId").asText());
        JsonNode snapshot = json.get("workOrderSnapshot");
        assertEquals(Set.of("totalPrice", "status", "observation", "description", "client", "vehicle", "materialItems", "laborItems"),
                fieldNames(snapshot));
        assertEquals(0, event.snapshot().totalPrice().compareTo(snapshot.get("totalPrice").decimalValue()));
        assertEquals("PENDING", snapshot.get("status").asText());
        assertEquals(Set.of("id", "name", "email", "document", "phone"), fieldNames(snapshot.get("client")));
        assertEquals(Set.of("licensePlate", "model", "brand", "modelYear"), fieldNames(snapshot.get("vehicle")));
        assertEquals(Set.of("id", "materialId", "quantity", "name", "brand", "description", "price", "type"),
                fieldNames(snapshot.get("materialItems").get(0)));
        assertEquals(Set.of("id", "workOrderId", "laborId", "executionStartAt", "executionEndAt", "status", "name", "price"),
                fieldNames(snapshot.get("laborItems").get(0)));
    }

    @Test
    @DisplayName("Deve mandar o preco unitario do material em price e o id da OS em cada tarefa")
    void shouldSendUnitPriceAndWorkOrderIdOnLaborItems() throws Exception {
        WorkOrderCreatedEvent event = created(workOrderId);

        JsonNode snapshot = mapper.readTree(serializer.toJson(GenerateBudgetIntegrationCommand.from(workOrderId, event.snapshot())))
                .get("workOrderSnapshot");

        JsonNode material = snapshot.get("materialItems").get(0);
        assertEquals(0, new java.math.BigDecimal("45.00").compareTo(material.get("price").decimalValue()));
        assertEquals(2, material.get("quantity").asInt());
        assertEquals(workOrderId.toString(), snapshot.get("laborItems").get(0).get("workOrderId").asText());
        assertEquals("AWAITING_EXECUTION", snapshot.get("laborItems").get(0).get("status").asText());
        assertFalse(snapshot.has("workOrder"));
    }
}
