# Como o saga funciona

> **Material de apoio.** Explicação técnica escrita durante o desenvolvimento; pode estar desatualizada em detalhes. A referência é o [README](../../README.md), que descreve o que foi entregue; as decisões estão nos [ADRs](../adr/) e [RFCs](../rfc/).

Este guia explica o saga do começo ao fim, na ordem em que as coisas acontecem. Se você só tem tempo para uma parte, leia as seções 1, 2 e 4.

---

## 1. A ideia em uma página

A ordem de serviço (OS) passa por três serviços:

- **Workorder API**: cuida da OS (criar, diagnosticar, executar, cancelar, entregar).
- **Finance API**: cuida do orçamento e do pagamento.
- **Saga API** (este projeto): **decide o que acontece depois de cada coisa**.

A Workorder e o Finance **não se conhecem**. Cada um só avisa o que aconteceu no seu domínio, publicando um **evento** ("a OS foi diagnosticada", "o orçamento foi aprovado"). O saga escuta todos esses eventos e responde com **commands** ("Finance, gere o orçamento", "Workorder, autorize a OS").

```
 Workorder API ──eventos──▶┐                       ┌──commands──▶ Workorder API
                           ├──▶   SAGA API   ──────┤
 Finance API ───eventos───▶┘                       └──commands──▶ Finance API
```

Isso é um **saga orquestrado**: existe um maestro central (o saga) que conhece o fluxo inteiro. Os outros dois serviços só tocam a sua parte.

O saga mantém **uma saga por OS**, identificada pelo `workOrderId`. Cada saga é um documento no MongoDB que guarda em que ponto a OS está no fluxo.

### Vocabulário

| Termo | Significado |
|---|---|
| **Evento** | Algo que **já aconteceu**, publicado por outro serviço. O saga só escuta. Ex.: `BudgetApprovedEvent`. |
| **Command** | Um **pedido** que o saga faz a outro serviço. O saga só publica. Ex.: `AuthorizeWorkOrderIntegrationCommand`. |
| **Estado** (`SagaState`) | Em que ponto exato a saga está. Ex.: `AWAITING_CUSTOMER_DECISION`. |
| **Etapa** (`SagaStep`) | Um grupo de estados do mesmo assunto. Ex.: a etapa `BUDGET` agrupa `AWAITING_BUDGET` e `BUDGET_GENERATED`. |
| **Regra de transição** | "Se chegar o evento X e a saga estiver assim, mude para o estado Y e envie o command Z." |
| **Compensação** | Desfazer um efeito já aplicado quando o fluxo volta atrás ou é cancelado. Ex.: cancelar o orçamento. |
| **Inbox / Outbox** | Tabelas (coleções) que garantem que nenhuma mensagem seja processada duas vezes nem se perca. |

---

## 2. O caminho de uma mensagem (do broker ao broker)

Esta é a parte mais importante. Toda mensagem faz exatamente este caminho:

```
RabbitMQ                                                            RabbitMQ
fila saga.workorder-events ─┐                          ┌─ exchange saga-output
fila saga.finance-events ───┤                          │      │
                            ▼                          │      ├─▶ workorder-input ─▶ fila workorder
              1. SagaEventsConsumer                    │      └─▶ finance-input ───▶ fila budget
                            ▼                          │
              2. MessageConsumer (inbox + transação)   │
                            ▼                          │
              3. ProcessSagaEventUseCase               │
                            ▼                          │
              4. SagaOrchestrator  ──▶ regras          │
                            ▼                          │
              5. salva a saga + grava commands na outbox
                            ▼                          │
              6. OutboxPublisher (a cada 1 s) ─────────┘
```

### Passo a passo

**1. Recebimento.** O `SagaEventsConsumer` escuta as duas filas de entrada (`saga.workorder-events` e `saga.finance-events`) com **um único consumer**, então uma instância processa um evento por vez. Antes de ele ser chamado, o `RabbitMQConfig` converte o corpo JSON na classe certa lendo o header `event-type` (o nome da classe do evento). Evento desconhecido ou sem o header vai direto para a DLQ.

**2. Inbox e transação.** O `MessageConsumer` (herdado do padrão do workorder-api) abre uma **transação do MongoDB** e, dentro dela, registra o `message-id` no inbox como `PROCESSED`. Se aquele `message-id` já estava `PROCESSED`, a mensagem é uma **duplicata**: ela recebe ack e nada mais acontece. Senão, segue para o passo 3.

**3. Caso de uso.** O `ProcessSagaEventUseCase` faz três coisas:

- Busca a saga da OS no MongoDB. Se ela existe, chama o orquestrador. Se não existe e o evento é o `WorkOrderCreatedEvent`, cria a saga. Se não existe e o evento é outro, lança `SagaNotFound` (a mensagem é recusada e pode ser reprocessada depois).
- Salva a saga.
- Para cada command que o orquestrador devolveu, grava uma linha na **outbox**.

**4. Orquestrador.** O `SagaOrchestrator` procura a regra de transição que casa com o evento e o estado atual, executa a regra e registra a transição no histórico. Detalhes na seção 4.

**5. Tudo na mesma transação.** Salvar a saga e gravar os commands na outbox acontecem **juntos ou nenhum dos dois**. Se qualquer coisa falhar, o MongoDB desfaz tudo, inclusive o registro no inbox.

**6. Publicação.** Fora da transação, o `OutboxPublisher` roda a cada segundo, pega as mensagens pendentes em ordem e as publica na exchange `saga-output` com os headers `message-id` e `event-type`, esperando a confirmação do broker. Depois marca como `PUBLISHED`. A `saga-output` repassa para a `workorder-input` e a `finance-input`, que entregam nas filas `workorder` e `budget` conforme o `event-type`.

### O que acontece quando algo dá errado

| Situação | O que o saga faz |
|---|---|
| Mensagem duplicada (mesmo `message-id` já `PROCESSED`) | Ack e ignora. |
| Evento de uma OS sem saga (que não seja `WorkOrderCreatedEvent`) | A transação é desfeita, o inbox grava `REJECTED`, ack. **Não vai para a DLQ.** Um reenvio com o mesmo `message-id` é reprocessado. |
| JSON ilegível, `event-type` desconhecido ou sem `message-id` | Vai para a DLQ da fila (`saga.workorder-events.dlq` ou `saga.finance-events.dlq`). |
| Erro técnico (banco fora, bug) | Transação desfeita, inbox grava `FAILED` com o motivo e a mensagem vai para a DLQ. Um reenvio com o mesmo `message-id` é reprocessado. |
| Duas instâncias alterando a mesma saga ao mesmo tempo | A segunda falha por **lock otimista** (campo `version`) e a mensagem vai para a DLQ. |
| Falha ao publicar uma mensagem da outbox | A linha vira `FAILED` e **não é reenviada sozinha**. |
| Evento que não faz sentido para o estado atual da saga | **Não é erro.** Nada muda, mas a transição é registrada no histórico com o mesmo estado de origem e de destino. |

---

## 3. Mapa do código

Tudo está em `src/main/java/com/mecanicadm/saga_api`.

```
core/                                  regras do saga, sem Spring, sem banco, sem RabbitMQ
  saga/
    event/                             os 24 eventos que o saga recebe (records)
    command/                           os 7 commands que o saga envia (records)
    domain/                            SagaInstance, SagaState, SagaStep, SagaTransition, snapshots, SagaGateway
    engine/                            o motor, genérico: não conhece nenhum step de negócio
    flow/                              o fluxo de negócio: um step por assunto, cada um com suas regras e sua compensação
    usecase/ProcessSagaEventUseCase    o único caso de uso
    exception/SagaExceptions           SagaNotFound
  shared/messaging/MessagePublisher    porta para publicar commands

infra/                                 tecnologia
  features/saga/
    amqp/SagaEventsConsumer            o consumer das duas filas
    config/SagaConfiguration           cria o orquestrador e o caso de uso
    persistence/                       SagaDocument e SagaRepositoryImpl (MongoDB)
  messaging/
    config/RabbitMQConfig              conversor de eventos pelo header event-type
    consumer/                          MessageConsumer (inbox, transação, ack/nack)
    inbox/                             InboxStore, InboxDocument
    outbox/                            OutboxStore, OutboxPublisher, RabbitOutboxMessageSender
    lock/DistributedLock               lock do publisher da outbox
```

**A regra de ouro:** o que está em `core/` não conhece Spring, MongoDB nem RabbitMQ. A regra "o que fazer quando o orçamento é aprovado" mora em `core/saga/flow`, e é só nesse lugar que você vai mexer para mudar o fluxo. O `core/saga/engine` é o motor genérico (escolhe a regra, planeja as compensações, registra o histórico) e não conhece nenhum step.

---

## 4. O orquestrador por dentro

Em vez de um grande `if` com todas as regras, o orquestrador é montado com peças pequenas.

### 4.1 Eventos e commands

São `record`s simples em `core/saga/event` e `core/saga/command`. O nome da classe é o `event-type` na mensagem. `SagaEvent` e `SagaCommand` são `sealed interface`: o compilador conhece a lista fechada de eventos e de commands.

Os eventos que o saga recebe:

- **Da Workorder (8):** `WorkOrderCreatedEvent`, `WorkOrderUpdatedEvent`, `WorkOrderDiagnosedEvent`, `WorkOrderExecutionStartedEvent`, `WorkOrderExecutionFinishedEvent`, `WorkOrderCanceledEvent`, `WorkOrderVehicleDeliveredEvent`, `WorkOrderCommandRejectedEvent`.
- **Do Finance (16):** `BudgetGeneratedEvent`, `BudgetUpdatedEvent`, `BudgetSentForDecisionEvent`, `BudgetSentForDecisionFailedEvent`, `BudgetApprovedEvent`, `BudgetRejectedEvent`, `BudgetChangesRequestedEvent`, `BudgetCanceledEvent`, `BudgetExpiredEvent`, `PaymentLinkGeneratedEvent`, `PaymentLinkGenerationFailedEvent`, `PaymentConfirmedEvent`, `PaymentFailedEvent`, `PaymentCanceledEvent`, `PaymentRefundedEvent`, `FinanceCommandRejectedEvent`.

Os commands que o saga envia:

- **Para a Workorder:** `AuthorizeWorkOrderIntegrationCommand`, `CancelWorkOrderIntegrationCommand`, `ChangesRequestedWorkOrderIntegrationCommand`, `ReleaseWorkOrderVehicleIntegrationCommand`.
- **Para o Finance:** `GenerateBudgetIntegrationCommand` (com o retrato da OS no formato do Finance: `workOrderId` e `workOrderSnapshot`), `CancelBudgetIntegrationCommand`, `GeneratePaymentIntegrationCommand`.

Todos carregam o `workOrderId`.

### 4.2 Estados e etapas

Cada estado pertence a uma etapa (`SagaStep`). As etapas seguem a ordem do negócio:

| Etapa | Estados | O que significa |
|---|---|---|
| `REGISTRATION` | `WORK_ORDER_OPEN` | OS criada, sendo montada. |
| `BUDGET` | `AWAITING_BUDGET`, `BUDGET_GENERATED` | O orçamento foi pedido ao Finance e ainda não foi enviado ao cliente. |
| `CUSTOMER_DECISION` | `AWAITING_CUSTOMER_DECISION`, `CHANGES_REQUESTED` | Orçamento enviado, esperando o cliente (ou a OS foi reaberta para ajuste). |
| `EXECUTION` | `AWAITING_EXECUTION`, `IN_EXECUTION` | Orçamento aprovado, serviço por começar ou em andamento. |
| `PAYMENT` | `AWAITING_PAYMENT_LINK`, `AWAITING_PAYMENT` | Serviço pronto, gerando e esperando o pagamento. |
| `DELIVERY` | `AWAITING_DELIVERY` | Pago, esperando a entrega do veículo. |

Estados fora das etapas:

| Estado | Significado |
|---|---|
| `CANCELING` | A saga está cancelando e espera a confirmação das compensações pendentes (OS, orçamento, link, estorno). |
| `PAUSED` | Falhou algo (envio do orçamento, link de pagamento, pagamento) ou o cancelamento da OS foi recusado, ou chegou um evento inesperado, e o saga espera uma intervenção manual. |
| `COMPLETED` | Fluxo concluído com sucesso (terminal). |
| `CANCELED` | Fluxo cancelado (terminal). |

Um evento que chega com a saga em `COMPLETED` ou `CANCELED` é ignorado, com uma exceção: o pagamento que chega depois do cancelamento (ver 4.8).

### 4.3 Regras de transição (`TransitionRule`)

Uma regra tem três partes: **o evento**, **uma guarda** (em que estado a regra vale) e **a ação**.

Exemplo real, da `BudgetStep`: quando a OS é diagnosticada e a saga está aberta, pedir o orçamento.

```java
TransitionRule.on(WorkOrderDiagnosedEvent.class, inState(SagaState.WORK_ORDER_OPEN), this::requestBudget)

private List<SagaCommand> requestBudget(SagaInstance saga, WorkOrderDiagnosedEvent event) {
    saga.moveTo(SagaState.AWAITING_BUDGET);
    return List.of(new GenerateBudgetIntegrationCommand(saga.getWorkOrderId(), saga.getWorkOrder()));
}
```

A ação muda o estado da saga e devolve a lista de commands a enviar (pode ser vazia).

As guardas prontas ficam em `Guards`: `inState(...)`, `inStep(...)`, `pausedFrom(...)`, `awaitingCompensation(...)` e `always()`.

**Ordem importa.** Para o mesmo evento pode haver mais de uma regra, e vale a **primeira cuja guarda passa**. A ordem é a das etapas na montagem (seção 4.4) e, dentro da etapa, a ordem em que as regras foram declaradas. Exemplo: o `WorkOrderUpdatedEvent` tem duas regras. A primeira vale quando a saga está nas etapas de orçamento ou decisão (compensa e reabre). A segunda vale em qualquer outro caso (só atualiza o retrato da OS).

### 4.4 As etapas do fluxo (`SagaFlowStep`)

Cada etapa é uma classe que devolve a lista de regras do seu assunto:

| Classe | Eventos que trata |
|---|---|
| `RegistrationStep` | `WorkOrderCreatedEvent`, `WorkOrderUpdatedEvent` |
| `BudgetStep` | `WorkOrderDiagnosedEvent`, `BudgetGeneratedEvent`, `BudgetUpdatedEvent`, `BudgetSentForDecisionEvent`, `BudgetSentForDecisionFailedEvent` |
| `CustomerDecisionStep` | `BudgetApprovedEvent`, `BudgetRejectedEvent`, `BudgetExpiredEvent`, `BudgetCanceledEvent`, `BudgetChangesRequestedEvent` |
| `ExecutionStep` | `WorkOrderExecutionStartedEvent`, `WorkOrderExecutionFinishedEvent` |
| `PaymentStep` | `PaymentLinkGeneratedEvent`, `PaymentLinkGenerationFailedEvent`, `PaymentConfirmedEvent`, `PaymentFailedEvent`, `PaymentCanceledEvent`, `PaymentRefundedEvent` |
| `DeliveryStep` | `WorkOrderVehicleDeliveredEvent` |
| `CancellationStep` | `WorkOrderCanceledEvent` |
| `CommandRejectionStep` | `WorkOrderCommandRejectedEvent`, `FinanceCommandRejectedEvent` |
| `UnexpectedEventStep` | Eventos à frente do estado da saga (ver 4.11). Declarada por último |

### 4.5 A montagem (`SagaDefinition`)

A `SagaDefinition` recebe a lista de etapas e junta todas as regras num único índice por tipo de evento. Ao ser criada, ela confere que **todo evento da saga tem pelo menos uma regra**. Se você criar um evento novo e esquecer a regra, a aplicação nem sobe: o erro lista os eventos sem regra.

A montagem oficial está em `SagaFlow.standard()` (pacote `flow`), que cria o `CompensationPlanner`, lista os steps na ordem e entrega a `SagaDefinition` ao `SagaOrchestrator`. O motor não monta nada sozinho.

### 4.6 O orquestrador (`SagaOrchestrator`)

Ele faz só isto, nesta ordem:

1. Guarda o estado atual da saga.
2. Se a saga já terminou, não decide nada.
3. Senão, pede à `SagaDefinition` a primeira regra que casa com o evento e a saga, e executa a ação. Sem regra que case, o evento é ignorado.
4. Registra no histórico da saga: evento, estado de origem, estado de destino e commands emitidos.
5. Devolve os commands.

Ele não tem nenhuma regra de negócio escrita nele.

### 4.7 A saga (`SagaInstance`)

É o agregado que guarda os dados e o histórico. Ele **não decide nada**: só oferece operações como `moveTo(estado)`, `pause(motivo)`, `registerBudget(total)`, `replaceWorkOrder(retrato)`. Quem decide são as regras.

O que ele guarda:

| Campo | Para que serve |
|---|---|
| `workOrderId` | Identifica a saga (é o `_id` no MongoDB). |
| `state`, `pausedFrom` | Estado atual e, se estiver pausada, de qual estado veio. |
| `workOrder` | O **retrato da OS** (cliente, veículo, mão de obra, materiais), atualizado a cada `WorkOrderCreatedEvent` e `WorkOrderUpdatedEvent`. |
| `budgetTotal` | O total do orçamento gerado pelo Finance. |
| `pendingCompensations` | As compensações que o saga enviou e ainda espera confirmar (ver 4.8). |
| `pauseReason` | O motivo da pausa. |
| `paymentLink` | O link de pagamento. |
| `version` | Lock otimista. |
| `history` | Uma entrada por evento processado. |

### 4.8 Compensação

Compensar é **desfazer um efeito já aplicado**. Cada passo que tem efeito em outro serviço tem a sua compensação, e o cancelamento da OS, em qualquer etapa, desfaz tudo o que já foi feito:

| Etapa concluída | O que foi feito | Como desfaz | Command |
|---|---|---|---|
| Abertura e autorização da OS, execução | OS aberta, autorizada, em execução | A Workorder cancela a OS | `CancelWorkOrderIntegrationCommand` (declarada no `RegistrationStep`) |
| Orçamento | Gerado, enviado, aprovado | O Finance cancela o orçamento | `CancelBudgetIntegrationCommand` (declarada no `BudgetStep`) |
| Pagamento pedido, não pago | Link gerado ou em geração | O Finance cancela o link | `CancelPaymentIntegrationCommand` (declarada no `PaymentStep`) |
| Pagamento confirmado | Cliente pagou, veículo ainda não entregue | O Finance estorna | `RefundPaymentIntegrationCommand` (declarada no `PaymentStep`) |
| Veículo entregue | Saga `COMPLETED` | Não se desfaz | Não se aplica |

O planner emite as compensações da etapa mais avançada para a mais antiga. Exemplo: cancelar com o pagamento confirmado envia `RefundPayment` e depois `CancelBudget`.


As peças:

- `Compensation` (no motor): diz a qual etapa pertence, quando se aplica e qual command desfaz.
- **Cada step declara a sua**, em `SagaFlowStep.compensations()`: o `RegistrationStep` o `CancelWorkOrder`, o `BudgetStep` o `CancelBudget` e o `PaymentStep` o `CancelPayment` e o `RefundPayment`. Quem não produz efeito em outro serviço não declara nada.
- `CompensationPlanner` (no motor): junta as compensações de todos os steps (`register`, feito uma vez em `SagaFlow.standard()`) e, a partir de um ponto de partida, devolve as aplicáveis, da etapa mais avançada para a mais antiga.

Quando a compensação roda:

| Gatilho | Ponto de partida | Resultado |
|---|---|---|
| `WorkOrderUpdatedEvent` com a saga em `BUDGET` ou `CUSTOMER_DECISION` | etapa `BUDGET` | Cancela o orçamento, descarta o total e volta para `WORK_ORDER_OPEN`. |
| `WorkOrderCanceledEvent` (cancelamento feito por alguém) | etapa `BUDGET` (a OS já foi cancelada) | Cancela o orçamento se existir e espera a confirmação. Sem nada a desfazer, vai direto para `CANCELED`. |
| `WorkOrderCanceledEvent` com o pagamento pedido ou confirmado | etapa `BUDGET` | Cancela o link ou estorna o pagamento, e também o orçamento. |
| Recusa de command em qualquer etapa | etapa `REGISTRATION` (inclui o `CancelWorkOrder`) | Desfaz o que já foi feito, pede o cancelamento da OS e vai para `CANCELING`. |

**A saga espera a confirmação das compensações.** O planner registra cada command de compensação como pendente na saga (`pendingCompensations`, gravada no MongoDB). Com a saga em `CANCELING`, ela só vai para `CANCELED` quando nada mais está pendente. Cada confirmação tira um item da lista:

| Compensação pendente | Confirma | Se o Finance recusar |
|---|---|---|
| `CancelWorkOrder` | `WorkOrderCanceledEvent` | A saga pausa |
| `CancelBudget` | `BudgetCanceledEvent` | Conclui: não havia orçamento a cancelar |
| `CancelPayment` | `PaymentCanceledEvent` | Conclui: não havia link a cancelar |
| `RefundPayment` | `PaymentRefundedEvent` | A saga pausa e loga `SAGA_COMPENSATION_FAILED` |

O `BudgetCanceledEvent` também significa "o orçamento expirou" (e aí a saga cancela a OS). A lista de pendências resolve a ambiguidade: se o `CancelBudget` está pendente, o evento é a resposta ao pedido da própria saga, e ela só o conclui. Sem isso, é expiração.

Uma saga `PAUSED` compensa pela etapa em que parou (`pausedFrom`). Uma saga pausada enquanto cancelava (estorno recusado) é encerrada quando a confirmação que faltava chegar, por exemplo depois de o Finance corrigir o estorno e publicar o `PaymentRefundedEvent`.

**Pagamento que chega depois do cancelamento.** Com a saga em `CANCELING`, o `PaymentConfirmedEvent` gera um `RefundPaymentIntegrationCommand` (o cliente pagou uma OS cancelada), que também entra nas pendências, e o `PaymentLinkGeneratedEvent` gera um `CancelPaymentIntegrationCommand`. Com a saga já em `CANCELED`, os commands são enviados, mas não há pendência. São as únicas regras que valem com a saga encerrada (`TransitionRule.afterTermination`).

### 4.9 Pausa e retomada

Quando algo falha do lado do Finance, o saga **não tenta resolver**: ele pausa e espera uma pessoa agir no Finance. A saga guarda de onde parou (`pausedFrom`) e só o evento que resolve aquela falha a retoma:

| Pausa | Causa | Quem retoma |
|---|---|---|
| Envio do orçamento | `BudgetSentForDecisionFailedEvent` | `BudgetSentForDecisionEvent` |
| Link de pagamento | `PaymentLinkGenerationFailedEvent` | `PaymentLinkGeneratedEvent` |
| Pagamento | `PaymentFailedEvent` | `PaymentConfirmedEvent` |

As pausas por recusa de command e por evento inesperado não têm evento de retomada. Um evento que não resolve aquela pausa é ignorado (por exemplo, `PaymentLinkGeneratedEvent` não retoma a pausa do envio do orçamento).

### 4.10 Recusa de command (`CommandRejectionStep`)

Quando a Workorder ou o Finance **recusa um command por regra de negócio**, ela publica `WorkOrderCommandRejectedEvent` ou `FinanceCommandRejectedEvent`, com `workOrderId`, `eventType` (o nome do command recusado), `exception` e `message`.

A regra vale em qualquer estado (`always()`), mas a saga **só reage se o command recusado for o que o estado atual está esperando**.

Uma recusa significa que o efeito do command **não aconteceu**: o que se desfaz são os passos anteriores, já concluídos em outro serviço. A reação depende de quanto a saga já avançou. O mapa estado → (command esperado, reação) está em `CommandRejectionStep`:

| Estado | Command esperado | Reação |
|---|---|---|
| `AWAITING_BUDGET` | `GenerateBudgetIntegrationCommand` | **Compensa e cancela a OS** |
| `AWAITING_EXECUTION` | `AuthorizeWorkOrderIntegrationCommand` | **Compensa e cancela a OS** |
| `CHANGES_REQUESTED` | `ChangesRequestedWorkOrderIntegrationCommand` | **Compensa e cancela a OS** |
| `AWAITING_PAYMENT_LINK` | `GeneratePaymentIntegrationCommand` | **Compensa e cancela a OS** |
| `AWAITING_DELIVERY` | `ReleaseWorkOrderVehicleIntegrationCommand` | **Compensa e cancela a OS** |
| `CANCELING` | `CancelWorkOrderIntegrationCommand` | Pausa (não há como compensar a compensação) |

"Compensa e cancela a OS" envia as compensações aplicáveis ao ponto em que a saga está (`RefundPayment`, `CancelPayment`, `CancelBudget`, da mais avançada para a mais antiga), depois o `CancelWorkOrderIntegrationCommand`, e vai para `CANCELING`. Assim a recusa de um command em qualquer etapa desfaz o que já foi feito. Exemplos:

- `GeneratePayment` recusado: `CancelPayment`, `CancelBudget` e `CancelWorkOrder`.
- `ReleaseWorkOrderVehicle` recusado (já pago): `RefundPayment`, `CancelBudget` e `CancelWorkOrder`.

A única recusa que pausa é a do próprio `CancelWorkOrderIntegrationCommand`: não há como compensar a compensação, então a saga espera uma pessoa.

Qualquer outra recusa não muda o fluxo e só entra no histórico. Exemplo: o Finance recusar o `CancelBudgetIntegrationCommand` de uma compensação porque não havia orçamento.

O motivo da recusa fica no log da compensação (`SAGA_COMPENSATED`) e, quando a saga pausa, no `pauseReason`: `<Origem>CommandRejected: <command> - <exception>: <message>`. Essa pausa não tem evento que a retome: é preciso intervenção manual (ver o README).

### 4.11 Evento inesperado (`UnexpectedEventStep`)

Quando chega um evento **à frente** do ponto em que a saga está (por exemplo, `PaymentConfirmedEvent` com a saga esperando a decisão do cliente), a saga pausa com o motivo `UnexpectedEvent: ...` em vez de ignorar em silêncio. Eventos atrasados ou duplicados continuam ignorados, assim como os de orçamento (que chegam fora de ordem de forma legítima quando a OS é alterada) e qualquer evento com a saga já pausada ou em `CANCELING`. Detalhes em [saga-fluxo.md](saga-fluxo.md).

**Falha técnica não compensa.** Banco fora, bug ou lock otimista não passam por aqui: a transação é desfeita e a mensagem vai para a DLQ, porque o evento já aconteceu no outro serviço.

### 4.12 Logs

Toda pausa gera um log `WARN` com a marca `SAGA_PAUSED`, e toda compensação gera `SAGA_COMPENSATED`, ambos com o `workOrderId` e o motivo. Um estorno recusado pelo Finance gera `SAGA_COMPENSATION_FAILED` (`ERROR`), mesmo com a saga já encerrada. É a forma de visualizar que uma saga aguarda intervenção, já que os outros serviços não consomem eventos da saga e ela não tem endpoints.

---

## 5. O fluxo completo, passo a passo

O caminho feliz, com os números do retrato de exemplo (mão de obra de 500,00 e materiais de 220,00, total 720,00):

| # | Quem fez | Evento que o saga recebe | Estado antes → depois | Command enviado |
|---|---|---|---|---|
| 1 | Workorder | `WorkOrderCreatedEvent` | (nova) → `WORK_ORDER_OPEN` | nenhum (guarda o retrato da OS) |
| 2 | Workorder | `WorkOrderDiagnosedEvent` | `WORK_ORDER_OPEN` → `AWAITING_BUDGET` | `GenerateBudget` (workOrderId e o retrato da OS, com mão de obra de 500,00 e materiais de 220,00) |
| 3 | Finance | `BudgetGeneratedEvent` | → `BUDGET_GENERATED` | nenhum |
| 4 | Finance | `BudgetSentForDecisionEvent` | → `AWAITING_CUSTOMER_DECISION` | nenhum |
| 5 | Finance | `BudgetApprovedEvent` | → `AWAITING_EXECUTION` | `AuthorizeWorkOrder` |
| 6 | Workorder | `WorkOrderExecutionStartedEvent` | → `IN_EXECUTION` | nenhum |
| 7 | Workorder | `WorkOrderExecutionFinishedEvent` | → `AWAITING_PAYMENT_LINK` | `GeneratePayment` |
| 8 | Finance | `PaymentLinkGeneratedEvent` | → `AWAITING_PAYMENT` | nenhum (guarda o link) |
| 9 | Finance | `PaymentConfirmedEvent` | → `AWAITING_DELIVERY` | `ReleaseWorkOrderVehicle` |
| 10 | Workorder | `WorkOrderVehicleDeliveredEvent` | → `COMPLETED` | nenhum |

O retrato do passo 2 é o guardado no passo 1 (e atualizado a cada `WorkOrderUpdatedEvent`), então o Finance recebe a OS como ela está no momento do pedido. O Finance calcula o total (720,00) somando o `price` de cada mão de obra com o `totalPrice` de cada material. A Workorder não calcula totais.

### Os 8 cenários de negócio

| # | Cenário | O que acontece no saga |
|---|---|---|
| 1 | OS alterada com orçamento gerado | `WorkOrderUpdatedEvent` → compensa: envia `CancelBudget`, descarta o total, volta para `WORK_ORDER_OPEN`. O orçamento novo nasce no próximo diagnóstico, com o total do retrato novo. |
| 2 | OS cancelada depois do orçamento enviado | `WorkOrderCanceledEvent` → compensa: `CancelBudget` e `CANCELED`. |
| 3 | Falha no envio do orçamento | `BudgetSentForDecisionFailedEvent` → `PAUSED`. Retoma com `BudgetSentForDecisionEvent`. |
| 4 | Falha ao gerar o link de pagamento | `PaymentLinkGenerationFailedEvent` → `PAUSED`. Retoma com `PaymentLinkGeneratedEvent`. |
| 5 | Cliente rejeita o orçamento | `BudgetRejectedEvent` → `CANCELING` e envia `CancelWorkOrder`. Ao chegar o `WorkOrderCanceledEvent`, vai para `CANCELED` **sem** cancelar o orçamento de novo (o Finance já o rejeitou). |
| 6 | Cliente pede alterações | `BudgetChangesRequestedEvent` → `CHANGES_REQUESTED` e envia `ChangesRequestedWorkOrder` com a observação. O mecânico edita a OS e chega o `WorkOrderUpdatedEvent`: compensa (`CancelBudget`) e volta a `WORK_ORDER_OPEN`. Edições seguintes só atualizam o retrato. No novo diagnóstico, o saga pede um orçamento novo. |
| 7 | OS cancelada com o serviço em andamento | `WorkOrderCanceledEvent` → compensa (`CancelBudget`) e `CANCELED`. |
| 8 | Orçamento expira | `BudgetCanceledEvent` sem o saga ter pedido o cancelamento → `CANCELING` e envia `CancelWorkOrder`. O `BudgetExpiredEvent` produz o mesmo efeito. |

---

## 6. O que fica salvo no MongoDB

| Coleção | Conteúdo |
|---|---|
| `saga_instances` | Uma saga por OS, com o retrato da OS e o **histórico embutido** (uma entrada por evento: qual evento, estado de origem, estado de destino, commands enviados). |
| `inbox` | Cada `message-id` já recebido e o resultado (`PROCESSED`, `REJECTED` ou `FAILED`). `REJECTED` e `FAILED` podem ser reprocessados. |
| `outbox` | Os commands a publicar, em ordem, com o status (`PENDING`, `PUBLISHED`, `FAILED`). |
| `locks` | O lock do publisher da outbox, para só uma instância publicar por vez. |

Para descobrir **por que uma OS está num estado**, olhe o campo `history` do documento dela em `saga_instances`: ele mostra, evento por evento, o que aconteceu e quais commands saíram.

O MongoDB precisa ser um **replica set**, mesmo com um nó só: salvar a saga e gravar a outbox na mesma transação só funciona assim. O `docker-compose.yml` já sobe o Mongo desse jeito.

---

## 7. A topologia do RabbitMQ

Todas as exchanges são do tipo `headers` e roteiam pelo header `event-type`. O arquivo `messaging/rabbitmq-definitions.json` descreve a topologia dos **três serviços** para importar de uma vez no broker.

| De | Para | O que passa |
|---|---|---|
| `saga-output` | `workorder-input` e `finance-input` | tudo (o filtro é feito depois) |
| `workorder-input` | fila `workorder` | os 4 commands para a Workorder |
| `finance-input` | fila `budget` | `GenerateBudgetIntegrationCommand`, `CancelBudgetIntegrationCommand`, `GeneratePaymentIntegrationCommand`, `CancelPaymentIntegrationCommand` e `RefundPaymentIntegrationCommand` |
| `workorder-output` | fila `saga.workorder-events` | os 7 eventos da Workorder |
| `finance-output` | fila `saga.finance-events` | os 13 eventos do Finance |

Cada fila tem uma DLQ (`<fila>.dlq`).

---

## 8. Como mexer

**Mudar o que o saga faz num evento.** Ache a etapa do evento (tabela da seção 4.4), abra a classe e edite a regra. Se a regra for nova para o mesmo evento, a ordem de declaração decide quem vale primeiro.

**Incluir um evento novo.**
1. Crie o `record` em `core/saga/event` e o inclua no `permits` do `SagaEvent`.
2. Declare a regra na etapa certa. Sem isso a aplicação não sobe (a `SagaDefinition` recusa).
3. Inclua o bind do evento na fila de entrada em `messaging/rabbitmq-definitions.json`.

**Incluir um command novo.** Crie o `record` em `core/saga/command`, inclua-o no `permits` do `SagaCommand` e retorne-o da regra. Crie o bind do command na exchange do serviço que o recebe.

**Incluir uma etapa nova.** Crie uma classe que implemente `SagaFlowStep`, inclua-a na lista de `SagaFlow.standard()` e, se for preciso, acrescente os valores no `SagaStep` e no `SagaState`.

**Incluir uma compensação nova.** Declare-a no step que produz o efeito, em `compensations()`: a etapa, quando se aplica e qual command desfaz. O planner a encontra sozinho. Se ela tem confirmação, crie o evento e a regra no mesmo step, que chama `saga.confirmCompensation(...)` e `saga.settleCancellation()`.

**Testar.** `mvn test` roda os testes unitários (as regras e a compensação não precisam de banco). `mvn verify` inclui os testes de integração, que sobem MongoDB e RabbitMQ em containers. Os cenários de negócio estão em `SagaOrchestratorTest`.

---

## 9. Limites conhecidos

- **Sem retry em conflito de lock otimista.** Se duas instâncias alterarem a mesma saga ao mesmo tempo, a segunda falha e vai para a DLQ.
- **Sem endpoint para retomar** saga pausada. O estado se consulta por `GET /api/sagas` (filtros por OS, situação, etapa, pausa e datas) e `GET /api/sagas/{workOrderId}` (com o histórico), ou direto no MongoDB.
- **A confirmação das compensações depende de o outro serviço publicá-la.** O Finance trata `CancelPayment` e `RefundPayment` e publica `PaymentCanceledEvent` e `PaymentRefundedEvent`; se um deles deixar de publicar, a saga fica em `CANCELING` esperando.
- **O resultado do pagamento chega ao Finance pelo webhook do Mercado Pago e por uma rotina de consulta** (reconciliação). Sem o webhook configurado, a latência é a do intervalo da rotina (1 minuto).
- **Pausa por recusa ou evento inesperado só se resolve na mão:** cancelando a OS ou ajustando o documento no MongoDB (ver o README).
- **Sem timeout de etapa.** Se um serviço nunca responder, a saga fica esperando.
- **Mensagem da outbox com falha na publicação** vira `FAILED` e não é reenviada sozinha.
- **Notificação ao cliente no cancelamento** (cenário 2): não há command definido, então não foi implementada.
- **Contrato com a Finance API:** a saga se adapta ao contrato do Finance (`workOrderSnapshot` no `GenerateBudgetIntegrationCommand`). Os detalhes estão em [saga-fluxo.md](saga-fluxo.md), seção "Alinhamento com a Finance API".

Para a tabela completa de transições (estado por evento), veja [saga-fluxo.md](saga-fluxo.md).
