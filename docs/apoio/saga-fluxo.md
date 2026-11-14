# Saga API: especificação do fluxo

> **Material de apoio.** Explicação técnica escrita durante o desenvolvimento; pode estar desatualizada em detalhes. A referência é o [README](../../README.md), que descreve o que foi entregue; as decisões estão nos [ADRs](../adr/) e [RFCs](../rfc/).
Especificação do fluxo (estados, transições, compensação, dados). A visão arquitetural está em [arquitetura-da-saga.md](arquitetura-da-saga.md), e as decisões, nos [ADRs](../adr/) e [RFCs](../rfc/).

A saga é um **orquestrador**: ela é a única peça que conhece o fluxo inteiro da OS. A Workorder API e a Finance API só emitem eventos sobre o que aconteceu no seu domínio. A saga escuta esses eventos e responde com **integration commands**. Há **uma instância de saga por OS**, identificada pelo `workOrderId` (o mesmo `workOrderId` nos eventos e commands da Workorder e do Finance).

## Estrutura do orquestrador

O `SagaOrchestrator` não tem regra de negócio escrita nele: ele só aplica as regras que as **etapas** do fluxo declaram. Tudo fica em `core/saga`:

| Peça | Papel |
|---|---|
| `SagaStep` | As etapas do negócio, em ordem: `REGISTRATION`, `BUDGET`, `CUSTOMER_DECISION`, `EXECUTION`, `PAYMENT`, `DELIVERY`. Cada `SagaState` pertence a uma etapa. |
| `SagaFlowStep` (no pacote `engine`) | O contrato de um step: `rules()` (as regras que reagem aos eventos) e `compensations()` (como desfazer o que o step fez). |
| `RegistrationStep`, `BudgetStep`, `CustomerDecisionStep`, `ExecutionStep`, `PaymentStep`, `DeliveryStep`, `CancellationStep`, `CommandRejectionStep`, `UnexpectedEventStep` (no pacote `flow`) | O **fluxo de negócio**. Cada step declara as regras do seu assunto e, quando produz efeito em outro serviço, a sua compensação. |
| `TransitionRule` | Um evento, uma condição (guarda) sobre o estado da saga e a ação, que muda o estado e devolve os commands. As regras de um mesmo evento são avaliadas na ordem em que foram declaradas, e vale a primeira cuja guarda passa. |
| `Guards` | As guardas reutilizáveis: por estado, por etapa, por estado de onde a saga foi pausada. |
| `SagaDefinition` | A **montagem do fluxo**: junta as regras de todas as etapas. Na criação, exige uma regra para cada evento da saga e falha se faltar alguma. |
| `SagaOrchestrator` | O motor: recebe a saga e o evento, aplica a regra que casa (ou ignora o evento), e registra a transição no histórico. Não conhece nenhum step. |
| `SagaFlow` | A **montagem do fluxo de negócio**: `SagaFlow.standard()` lista os steps na ordem, registra as compensações no planner e devolve o orquestrador pronto. |
| `Compensation`, `CompensationPlanner` | A **compensação**: diz a qual etapa pertence, quando se aplica e qual command desfaz o efeito. Cada step declara as suas. O planejador junta as de todos os steps, compensa da etapa mais avançada para a mais antiga e registra cada command como pendente na saga. |
| `SagaInstance` | O agregado: dados, histórico e operações de intenção (`moveTo`, `pause`, `registerBudget`...). Não decide nada sozinho. |

Para incluir um passo novo no fluxo: criar o evento (em `core/saga/event` e na lista `permits` do `SagaEvent`), declarar a regra no step certo e, se o passo tiver efeito a desfazer, a compensação no mesmo step (`compensations()`), com a regra que a confirma.

### Compensação

A compensação é acionada em dois casos:

- **Alteração da OS com orçamento ativo** (etapas `BUDGET` e `CUSTOMER_DECISION`): compensa só a partir da etapa de orçamento, descarta o total e reabre a saga.
- **Cancelamento da OS** (`WorkOrderCanceledEvent`): compensa tudo o que já foi feito, em qualquer etapa (ver a tabela abaixo).
- **Recusa de command em qualquer etapa** (exceto a do próprio cancelamento): desfaz o que já foi feito e pede o cancelamento da OS (ver "Recusa de command").

Toda compensação é registrada no log (`SAGA_COMPENSATED`, ver "Logs e alertas").

| Etapa concluída | O que foi feito | Como desfaz | Command |
|---|---|---|---|
| Abertura e autorização da OS, execução | OS aberta, autorizada, em execução | A Workorder cancela a OS | `CancelWorkOrderIntegrationCommand` (declarada no `RegistrationStep`) |
| Orçamento | Gerado, enviado, aprovado | O Finance cancela o orçamento | `CancelBudgetIntegrationCommand` (declarada no `BudgetStep`) |
| Pagamento pedido, não pago | Link gerado ou em geração | O Finance cancela o link | `CancelPaymentIntegrationCommand` (declarada no `PaymentStep`) |
| Pagamento confirmado | Cliente pagou, veículo ainda não entregue | O Finance estorna | `RefundPaymentIntegrationCommand` (declarada no `PaymentStep`) |
| Veículo entregue | Saga `COMPLETED` | Não se desfaz | Não se aplica |

O planner emite as compensações da etapa mais avançada para a mais antiga. Exemplo: cancelar com o pagamento confirmado envia `RefundPayment` e depois `CancelBudget`.

**Pagamento que chega depois do cancelamento.** O cliente pode pagar entre o cancelamento e o cancelamento do link, ou o Finance pode gerar o link depois de a saga cancelar. Por isso, com a saga em `CANCELING` ou `CANCELED`:

- `PaymentConfirmedEvent` → `RefundPaymentIntegrationCommand` (o dinheiro entrou para uma OS cancelada);
- `PaymentLinkGeneratedEvent` → `CancelPaymentIntegrationCommand`.

São as únicas regras (`TransitionRule.afterTermination`) que valem com a saga encerrada. Qualquer outro evento em `COMPLETED` ou `CANCELED` continua ignorado.

**A saga espera a confirmação das compensações.** Cada command de compensação enviado fica registrado como pendente na saga (`pendingCompensations`, no documento do MongoDB). A saga entra em `CANCELING` e só vai para `CANCELED` quando nada mais está pendente:

| Compensação pendente | Confirma | Se o Finance recusar |
|---|---|---|
| `CancelWorkOrder` | `WorkOrderCanceledEvent` | A saga pausa |
| `CancelBudget` | `BudgetCanceledEvent` | Conclui: não havia orçamento a cancelar |
| `CancelPayment` | `PaymentCanceledEvent` | Conclui: não havia link a cancelar |
| `RefundPayment` | `PaymentRefundedEvent` | A saga pausa e loga `SAGA_COMPENSATION_FAILED` (`ERROR`) |

Sem nada a compensar, a saga vai direto para `CANCELED`. Uma saga pausada por estorno recusado é encerrada quando a confirmação chegar depois (por exemplo, quando o Finance corrigir o estorno e publicar o `PaymentRefundedEvent`). O `BudgetCanceledEvent` também significa "o orçamento expirou": se o `CancelBudget` está pendente, ele é a resposta ao pedido da saga, e só então.
Uma saga pausada compensa pela etapa em que parou.

### Pausa e retomada

A saga pausada guarda de qual estado veio (`pausedFrom`) e o motivo (`pauseReason`). Só o evento que resolve aquela falha a retoma: o `BudgetSentForDecisionEvent` retoma a pausa de envio do orçamento, o `PaymentLinkGeneratedEvent` retoma a falha do link, e o `PaymentConfirmedEvent` retoma a falha de pagamento.

As pausas por **recusa do cancelamento da OS** e por **evento inesperado** não têm evento que as retome: exigem intervenção manual (ver "Retomando uma saga pausada" no README). Toda pausa é registrada no log (`SAGA_PAUSED`).

## Como uma mensagem é tratada

1. O evento chega numa das duas filas: `saga.workorder-events` (eventos da Workorder) ou `saga.finance-events` (eventos do Finance).
2. O conversor escolhe a classe pelo header `event-type`, que é o nome da classe do evento. O `message-id` (header) é a chave do inbox.
3. O `ProcessSagaEventUseCase`, numa transação, carrega a saga (ou cria uma no `WorkOrderCreatedEvent`), chama o orquestrador, salva o novo estado e grava os commands na outbox.
4. O publisher da outbox envia os commands para a exchange `saga-output`.

Regras herdadas do padrão do projeto: inbox para idempotência, outbox transacional, falha técnica vai para a DLQ, recusa de negócio (por exemplo, evento de OS sem saga) recebe ack com `REJECTED` no inbox, e uma mensagem `REJECTED` ou `FAILED` pode ser reprocessada com o mesmo `message-id`.

## Estados e transições

| Evento recebido | Em qual estado | Command emitido | Novo estado |
|---|---|---|---|
| `WorkOrderCreatedEvent` | (saga não existe) | nenhum | `WORK_ORDER_OPEN` |
| `WorkOrderUpdatedEvent` | `AWAITING_BUDGET`, `BUDGET_GENERATED`, `AWAITING_CUSTOMER_DECISION`, `CHANGES_REQUESTED` | `CancelBudgetIntegrationCommand` | `WORK_ORDER_OPEN` |
| `WorkOrderUpdatedEvent` | qualquer outro | nenhum (só atualiza o retrato da OS) | mantém |
| `WorkOrderDiagnosedEvent` | `WORK_ORDER_OPEN` | `GenerateBudgetIntegrationCommand` (`workOrderId`, `workOrderSnapshot`) | `AWAITING_BUDGET` |
| `BudgetGeneratedEvent` | `AWAITING_BUDGET` | nenhum | `BUDGET_GENERATED` |
| `BudgetUpdatedEvent` | `BUDGET_GENERATED`, `AWAITING_CUSTOMER_DECISION` | nenhum (atualiza o total) | mantém |
| `BudgetSentForDecisionEvent` | `BUDGET_GENERATED`, `PAUSED` | nenhum | `AWAITING_CUSTOMER_DECISION` |
| `BudgetSentForDecisionFailedEvent` | `BUDGET_GENERATED` | nenhum | `PAUSED` |
| `BudgetApprovedEvent` | `AWAITING_CUSTOMER_DECISION` | `AuthorizeWorkOrderIntegrationCommand` | `AWAITING_EXECUTION` |
| `BudgetRejectedEvent`, `BudgetExpiredEvent` | `AWAITING_CUSTOMER_DECISION` | `CancelWorkOrderIntegrationCommand` | `CANCELING` |
| `BudgetCanceledEvent` | `BUDGET_GENERATED`, `AWAITING_CUSTOMER_DECISION` (cancelamento que a saga não pediu) | `CancelWorkOrderIntegrationCommand` | `CANCELING` |
| `BudgetCanceledEvent` | quando a saga pediu o cancelamento | nenhum | mantém |
| `BudgetChangesRequestedEvent` | `AWAITING_CUSTOMER_DECISION` | `ChangesRequestedWorkOrderIntegrationCommand` (`observation`) | `CHANGES_REQUESTED` |
| `WorkOrderExecutionStartedEvent` | `AWAITING_EXECUTION` | nenhum | `IN_EXECUTION` |
| `WorkOrderExecutionFinishedEvent` | `IN_EXECUTION` | `GeneratePaymentIntegrationCommand` | `AWAITING_PAYMENT_LINK` |
| `PaymentLinkGeneratedEvent` | `AWAITING_PAYMENT_LINK`, `PAUSED` | nenhum | `AWAITING_PAYMENT` |
| `PaymentLinkGenerationFailedEvent` | `AWAITING_PAYMENT_LINK` | nenhum | `PAUSED` |
| `PaymentConfirmedEvent` | `AWAITING_PAYMENT` | `ReleaseWorkOrderVehicleIntegrationCommand` | `AWAITING_DELIVERY` |
| `PaymentFailedEvent` | `AWAITING_PAYMENT` | nenhum | `PAUSED` |
| `WorkOrderVehicleDeliveredEvent` | `AWAITING_DELIVERY` | nenhum | `COMPLETED` |
| `WorkOrderCanceledEvent` | `CANCELING` (ou pausada enquanto cancelava) | nenhum (conclui o `CancelWorkOrder`) | `CANCELED`, se não resta compensação pendente |
| `WorkOrderCanceledEvent` | `WORK_ORDER_OPEN` | nenhum | `CANCELED` |
| `WorkOrderCanceledEvent` | qualquer outro | As compensações aplicáveis: `RefundPayment`, `CancelPayment` e `CancelBudget` | `CANCELING`, e `CANCELED` quando forem confirmadas (ou já `CANCELED`, se não houver nenhuma) |
| `BudgetCanceledEvent`, `PaymentCanceledEvent`, `PaymentRefundedEvent` | a saga espera a confirmação desse command | nenhum | mantém, e `CANCELED` se era a última pendência com a saga cancelando |
| `PaymentConfirmedEvent` | `CANCELING`, `CANCELED` | `RefundPaymentIntegrationCommand` (em `CANCELING`, também fica pendente) | mantém |
| `PaymentLinkGeneratedEvent` | `CANCELING`, `CANCELED` | `CancelPaymentIntegrationCommand` | mantém |
| `WorkOrderCommandRejectedEvent`, `FinanceCommandRejectedEvent` | o estado espera o command recusado, exceto `CANCELING` | As compensações aplicáveis e `CancelWorkOrderIntegrationCommand` | `CANCELING` |
| `WorkOrderCommandRejectedEvent` | `CANCELING`, com `CancelWorkOrderIntegrationCommand` recusado | nenhum | `PAUSED` |
| `WorkOrderCommandRejectedEvent`, `FinanceCommandRejectedEvent` | qualquer outro | nenhum | mantém |

Um evento que não se aplica ao estado atual **não muda nada**: ele é registrado no histórico com o mesmo estado de origem e de destino. Em `COMPLETED` e `CANCELED` todo evento é ignorado, exceto o pagamento que chega depois do cancelamento e a recusa do estorno (ver "Compensação").

### Recusa de command (`*CommandRejectedEvent`)

Quando a Workorder ou o Finance recusa um command por regra de negócio, ela emite `WorkOrderCommandRejectedEvent` ou `FinanceCommandRejectedEvent`, com `workOrderId`, `eventType` (o nome do command recusado), `exception` (a exceção de domínio) e `message` (o motivo). A saga só reage se o command recusado for o que o estado atual está esperando; qualquer outra recusa (por exemplo, o Finance recusar um `CancelBudgetIntegrationCommand` de compensação) só entra no histórico.

Uma recusa significa que o efeito do command **não aconteceu**. O que se desfaz são os passos anteriores, já concluídos em outro serviço. A reação depende de quanto a saga já avançou:

| Estado | Command esperado | Reação | Por quê |
|---|---|---|---|
| `AWAITING_BUDGET` | `GenerateBudgetIntegrationCommand` | **Compensa e cancela a OS** | O pedido de orçamento falhou. Cancela o que possa existir e a OS |
| `AWAITING_EXECUTION` | `AuthorizeWorkOrderIntegrationCommand` | **Compensa e cancela a OS** | O orçamento já foi aprovado no Finance e perdeu o sentido |
| `CHANGES_REQUESTED` | `ChangesRequestedWorkOrderIntegrationCommand` | **Compensa e cancela a OS** | O orçamento já foi enviado ao cliente |
| `AWAITING_PAYMENT_LINK` | `GeneratePaymentIntegrationCommand` | **Compensa e cancela a OS** | Cancela o link, o orçamento e a OS |
| `AWAITING_DELIVERY` | `ReleaseWorkOrderVehicleIntegrationCommand` | **Compensa e cancela a OS** | Estorna o pagamento, cancela o orçamento e a OS |
| `CANCELING` | `CancelWorkOrderIntegrationCommand` | Pausa | Não há como compensar a própria compensação |

Compensar significa: enviar as compensações aplicáveis (incluindo o `CancelWorkOrderIntegrationCommand`) e ir para `CANCELING`, esperando as confirmações. Se a Workorder recusar também o cancelamento, a saga pausa.

O `pauseReason` (e o motivo no log) fica `<Origem>CommandRejected: <command> - <exception>: <message>`. A política está no mapa `AWAITED_COMMAND` da `CommandRejectionStep`, e para trocar a reação de um estado basta mudar a entrada dele.

### Evento inesperado (`UnexpectedEventStep`)

A saga pausa quando chega um evento **à frente** do ponto em que ela está, o que indica uma anomalia. Exemplo: `PaymentConfirmedEvent` com a saga ainda em `AWAITING_CUSTOMER_DECISION`. O `pauseReason` fica `UnexpectedEvent: <evento> recebido no estado <estado> (etapa <etapa>)`.

Continuam **ignorados**, sem pausar:

- eventos atrasados ou duplicados (de uma etapa que a saga já passou ou da etapa atual);
- eventos da etapa de orçamento e o `BudgetCanceledEvent`, que chegam fora de ordem de forma legítima quando a OS é alterada logo depois do pedido do orçamento;
- eventos de decisão do orçamento quando a própria saga pediu o cancelamento do orçamento;
- qualquer evento com a saga já pausada ou em `CANCELING`.

Esta regra vale só quando nenhuma regra de negócio casou, e é a última da montagem.

**Falha técnica não compensa.** Banco fora, bug ou conflito de lock otimista não passam por aqui: a transação é desfeita e a mensagem vai para a DLQ (ver "Como uma mensagem é tratada"). O evento já aconteceu no outro serviço, então o caminho é reprocessar, e não desfazer.

### Logs e alertas

Como os serviços não têm tela nem consomem eventos da saga, a visibilidade vem dos logs. Cada linha tem a marca fixa no início, para filtro e alerta:

| Marca | Quando | Campos |
|---|---|---|
| `SAGA_PAUSED` | A saga entra em `PAUSED` | `workOrderId`, `pausedFrom`, `event`, `reason` |
| `SAGA_COMPENSATED` | A saga emite comandos de compensação | `workOrderId`, `state`, `reason`, `commands` |
| `SAGA_COMPENSATION_FAILED` | O Finance recusou o estorno (`ERROR`) | `workOrderId`, `state`, `command`, `reason` |

As duas primeiras são `WARN` e a de falha de estorno é `ERROR`. O agente do New Relic encaminha os logs da aplicação (`application_logging.forwarding` está ligado em `newrelic/newrelic.yml`). Consulta de exemplo: `SELECT * FROM Log WHERE message LIKE 'SAGA_PAUSED%'`. O alerta em si é criado no New Relic, não neste repositório.

### Como os cenários de negócio aparecem na tabela

- **Cenário 1 e 6 (alteração da OS):** o `WorkOrderUpdatedEvent` com orçamento ativo cancela o orçamento e reabre a saga. O orçamento novo nasce no diagnóstico seguinte, com o total do retrato atualizado. Um segundo `Updated` seguido do primeiro não cancela de novo.
- **Cenário 2 e 7 (cancelamento da OS):** o `WorkOrderCanceledEvent` cancela o orçamento e encerra a saga, em qualquer fase.
- **Cenário 3 e 4 (falhas de envio e de link):** a saga só pausa. Quem retoma é o Finance, quando a intervenção manual funciona e ele emite o `BudgetSentForDecisionEvent` ou o `PaymentLinkGeneratedEvent`.
- **Cenário 5 e 8 (rejeição e expiração):** a saga pede o cancelamento da OS e conclui no `WorkOrderCanceledEvent`, sem cancelar o orçamento de novo.

## Dados guardados (MongoDB)

O saga usa **MongoDB**, que precisa rodar como **replica set** (mesmo com um nó só): a gravação da saga e a da outbox acontecem na mesma transação multi-documento, e o MongoDB só oferece transações em replica set. O driver usa UUID no formato `standard`.

| Coleção | Conteúdo |
|---|---|
| `saga_instances` | Um documento por OS (`_id` = `workOrderId`): estado, retrato da OS (documento embutido, atualizado a cada Created e Updated), total do orçamento, indicador de cancelamento de orçamento pedido, estado de onde foi pausada, motivo da pausa, link de pagamento, `version` (lock otimista) e o **histórico** embutido (uma entrada por evento, com evento, estado de origem, estado de destino e commands emitidos). |
| `inbox` | Idempotência dos eventos recebidos, por `message-id`. `PROCESSED` é duplicata; `REJECTED` e `FAILED` podem ser reprocessados. |
| `outbox` | Commands a publicar, ordenados por `sequence` (relógio em microssegundos, monotônico por instância). |
| `locks` | Lease do publisher da outbox. |

O `GenerateBudgetIntegrationCommand` segue o contrato do Finance (`workOrderId` e `workOrderSnapshot`) e é montado a partir do retrato da OS guardado na saga (`BudgetWorkOrderSnapshot.from`):

```json
{
  "workOrderId": "<uuid>",
  "workOrderSnapshot": {
    "totalPrice", "status", "observation", "description", "client", "vehicle",
    "materialItems": [{ "id", "materialId", "quantity", "name", "brand", "description", "price", "type" }],
    "laborItems": [{ "id", "workOrderId", "laborId", "executionStartAt", "executionEndAt", "status", "name", "price" }]
  }
}
```

- `totalPrice` é a soma da mão de obra e dos materiais do retrato (calculada pela saga). O `price` do material é o **preço unitário**.
- `status` é sempre `PENDING`, o status inicial do orçamento no Finance (o `status` da OS não é enviado, porque o enum do Finance é outro).
- `observation` vai `null`. O `status` das tarefas usa os mesmos nomes da Workorder (`AWAITING_EXECUTION`, `IN_EXECUTION`, `EXECUTION_COMPLETED`).
- O retrato é o mais recente recebido em um `WorkOrderCreatedEvent` ou `WorkOrderUpdatedEvent`, então um orçamento refeito depois de alterações leva a OS alterada. O total enviado é sempre calculado pela saga: a Workorder API não soma nada.

**Concorrência.** Um único consumer escuta as duas filas de entrada, então uma instância processa um evento por vez. Se duas instâncias processarem eventos da mesma OS ao mesmo tempo, a segunda falha por lock otimista, a mensagem vai para a DLQ e pode ser reprocessada.

**Publicação da outbox.** O publisher só trabalha com o lock `outbox-publisher` (lease calculado pelo tamanho do lote e pelo timeout de confirmação), então a ordem de envio se mantém mesmo com várias instâncias. Se uma instância cair com o lock, ele expira sozinho.

## Topologia do RabbitMQ

Todas as exchanges são do tipo `headers` e o roteamento usa o header `event-type`. O arquivo `messaging/rabbitmq-definitions.json` descreve **toda** a topologia dos três serviços, para importar de uma vez no broker compartilhado:

| De | Para | Filtro |
|---|---|---|
| `saga-output` | `workorder-input` e `finance-input` | tudo (exchange para exchange) |
| `workorder-input` | fila `workorder` | os 4 commands da Workorder |
| `finance-input` | fila `budget` | `GenerateBudgetIntegrationCommand`, `CancelBudgetIntegrationCommand`, `GeneratePaymentIntegrationCommand`, `CancelPaymentIntegrationCommand` e `RefundPaymentIntegrationCommand` |
| `workorder-output` e `finance-output` | `saga-input` | tudo (exchange para exchange) |
| `saga-input` | fila `saga.workorder-events` | os 8 eventos da Workorder (incluindo `WorkOrderCommandRejectedEvent`) |
| `saga-input` | fila `saga.finance-events` | os 16 eventos do Finance (incluindo `PaymentCanceledEvent`, `PaymentRefundedEvent` e `FinanceCommandRejectedEvent`) |

Cada fila tem uma DLQ (`<fila>.dlq`) pela exchange `mecanicadm.dlx`.

### Broker na nuvem (CloudAMQP)

1. Crie a instância e anote host, porta `5671` (TLS), usuário, senha e vhost.
2. No painel RabbitMQ Manager, em *Overview > Import definitions*, importe `messaging/rabbitmq-definitions.json`. Se o vhost da instância não for `/`, escolha o vhost de destino na importação.
3. Em cada serviço, configure `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` e `RABBITMQ_SSL_ENABLED=true`.

## Alinhamento com a Finance API

Conferido contra o código atual da `mecanicadm-finance-api-v4`. Tudo abaixo está implementado nos dois lados:

- **Identificação das mensagens.** Header `event-type` com o nome da classe, exchanges `finance-input` e `finance-output`, campo `workOrderId` em tudo. O Finance usa o header `message-id` como chave do inbox (cai para a propriedade AMQP `message_id` só se o header faltar).
- **Filas do Finance.** Os commands de orçamento (`GenerateBudgetIntegrationCommand`, `CancelBudgetIntegrationCommand`) vão para a fila `budget`; os de pagamento (`GeneratePaymentIntegrationCommand`, `CancelPaymentIntegrationCommand`, `RefundPaymentIntegrationCommand`) vão para a fila `payment`. Cada uma tem a sua DLQ pela `finance-dlx`.
- **`GenerateBudgetIntegrationCommand`** leva `workOrderId` e `workOrderSnapshot`, exatamente o contrato do Finance (`WorkOrderSnapshot` com `totalPrice`, `status`, `client`, `vehicle`, `materialItems` com `price` unitário e `laborItems`). A saga monta o snapshot a partir do retrato da OS e o Finance monta o orçamento com ele. O Finance não escuta eventos da Workorder (nem `WorkOrderCreatedEvent`, nem `WorkOrderUpdatedEvent`). Um novo comando para a mesma OS refaz o orçamento com os itens atualizados.
- **`GeneratePaymentIntegrationCommand`** (`workOrderId`): o Finance usa o total e o cliente do orçamento da OS para gerar o link e responde com `PaymentLinkGeneratedEvent` ou `PaymentLinkGenerationFailedEvent`. Sem orçamento, recusa.
- **`CancelPaymentIntegrationCommand`**: cancela o pedido ainda não pago no Mercado Pago e publica `PaymentCanceledEvent`. É idempotente: sem pagamento (link ainda não gerado) ou já cancelado, só confirma. Pagamento já confirmado é recusado, porque só se estorna.
- **`RefundPaymentIntegrationCommand`**: estorna o pagamento confirmado e publica `PaymentRefundedEvent`. Idempotente para pagamento já estornado. Sem pagamento ou não confirmado, recusa (a saga pausa e registra `SAGA_COMPENSATION_FAILED`).
- **`CancelBudgetIntegrationCommand`** continua publicando o `BudgetCanceledEvent`, que a saga usa para encerrar o cancelamento.
- **Pagamento depois do cancelamento:** o Finance publica `PaymentConfirmedEvent` normalmente e a saga reage com o estorno.
- **Recusas:** o Finance publica `FinanceCommandRejectedEvent` (`workOrderId`, `eventType`, `exception`, `message`) quando um listener falha, e a Workorder publica `WorkOrderCommandRejectedEvent` com os mesmos campos.
- **O Finance ainda publica `messageId` e `ocurredAt` no corpo dos eventos.** A saga ignora esses campos.

## Decisões e pontos em aberto

- **Expiração do orçamento.** O Finance publica o `BudgetExpiredEvent` quando o link de decisão vence sem resposta (24 horas), por uma rotina que avisa uma única vez; a saga cancela a OS e o cancelamento do orçamento é confirmado pelo `BudgetCanceledEvent` (RFC 001 da Finance API). Com isso, os cenários 5 e 8 se completam sem rejeição explícita do cliente.
- **Eventos de pagamento.** A saga lê só o `workOrderId`, o `paymentLink` e o `reason`, que batem com os eventos do Finance. O `PaymentFailedEvent` pausa a saga.
- **`BudgetCanceledEvent` tem dois sentidos.** Ele serve tanto para um cancelamento que a saga não pediu (a saga deve cancelar a OS) quanto para a resposta ao `CancelBudgetIntegrationCommand` (a saga não deve fazer nada). A saga distingue guardando que pediu o cancelamento. A expiração do orçamento tem evento próprio, o `BudgetExpiredEvent`. Se o Finance incluísse o motivo no evento, isso ficaria mais robusto.
- **Fim do fluxo.** Depois do `PaymentConfirmedEvent` a saga manda `ReleaseWorkOrderVehicleIntegrationCommand`, e a entrega é manual na Workorder API (`WorkOrderVehicleDeliveredEvent` encerra a saga).
- **Notificação ao cliente no cancelamento (cenário 2):** não está implementada porque não há command definido para isso.
- **Consultar o estado:** `GET /api/sagas` (listagem com filtros por OS, situação, etapa, pausa e datas) e `GET /api/sagas/{workOrderId}` (com o histórico). A API só consulta e exige o JWT da Workorder (`JWT_SECRET` compartilhado). **Retomar sagas pausadas:** não há endpoint; a retomada segue o descrito no README.
- **Recusas da Workorder API.** A Workorder emite o `WorkOrderCommandRejectedEvent` pela outbox, na mesma transação que grava o `REJECTED` no inbox. Uma mensagem `REJECTED` reprocessada com o mesmo `message-id` emite o evento de novo.
