# Arquitetura técnica do saga

> **Material de apoio.** Explicação técnica escrita durante o desenvolvimento; pode estar desatualizada em detalhes. A referência é o [README](../../README.md), que descreve o que foi entregue; as decisões estão nos [ADRs](../adr/) e [RFCs](../rfc/).

Este documento descreve a arquitetura do `mecanicadm-saga-api-v4` do ponto de vista do código: cada arquivo, onde ele entra no fluxo e como funciona. Ele segue o caminho de uma mensagem, do início ao fim. Para a visão de negócio (estados, cenários, compensação), veja [como-o-saga-funciona.md](como-o-saga-funciona.md). Para a tabela de transições, veja [saga-fluxo.md](saga-fluxo.md).

## Sumário

1. [Stack e decisões](#1-stack-e-decisões)
2. [Camadas e regra de dependência](#2-camadas-e-regra-de-dependência)
3. [Inicialização: o que o Spring monta](#3-inicialização-o-que-o-spring-monta)
4. [A jornada de uma mensagem, método a método](#4-a-jornada-de-uma-mensagem-método-a-método)
5. [Catálogo de arquivos](#5-catálogo-de-arquivos)
6. [Modelo de dados (MongoDB)](#6-modelo-de-dados-mongodb)
7. [Contratos de mensagem](#7-contratos-de-mensagem)
8. [Transações, idempotência e concorrência](#8-transações-idempotência-e-concorrência)
9. [Configuração e infraestrutura](#9-configuração-e-infraestrutura)
10. [Testes](#10-testes)

---

## 1. Stack e decisões

| Item | Escolha | Motivo |
|---|---|---|
| Linguagem e framework | Java 21, Spring Boot 3.4 | Mesmo padrão do workorder-api. O Java 21 permite `sealed interface` com `switch` e `record`. |
| Banco | MongoDB 7, como replica set | A saga e a outbox são gravadas na mesma **transação multi-documento**, que o MongoDB só oferece em replica set. |
| Broker | RabbitMQ (CloudAMQP em produção) | Exchanges do tipo `headers`, roteadas pelo header `event-type`. |
| Estilo | Clean Architecture (`core` e `infra`) | Mesmo padrão do workorder-api. A regra de negócio não conhece tecnologia. |
| Padrão de mensageria | Inbox + Outbox transacionais | Nenhuma mensagem é processada duas vezes e nenhum command se perde. |
| Modelo de saga | Orquestrado, com uma saga por OS | O saga é o único que conhece o fluxo inteiro. |
| API HTTP | Só consulta: `GET /api/sagas` e `GET /api/sagas/{workOrderId}` (mais Swagger, actuator e health) | O fluxo é conduzido só por mensagens. A API não altera nem retoma sagas e exige o JWT da Workorder (segredo `JWT_SECRET` compartilhado); Swagger, actuator e health são públicos. |

---

## 2. Camadas e regra de dependência

```
        ┌──────────────────────────── infra ────────────────────────────┐
        │  Spring, MongoDB, RabbitMQ                                    │
        │                                                               │
        │   amqp (consumer)   persistence (Mongo)   messaging (inbox,  │
        │                                           outbox, lock, conf) │
        └───────────────┬───────────────────────────────┬───────────────┘
                        │ depende de                    │ implementa
                        ▼                               │
        ┌──────────────────────────── core ─────────────┴───────────────┐
        │  Java puro: nada de Spring, MongoDB ou RabbitMQ               │
        │                                                               │
        │   usecase ──▶ orchestrator ──▶ domain         event, command  │
        │                    ▲                                          │
        │                    └── step (regras), compensation            │
        │   portas: SagaGateway, MessagePublisher                       │
        └───────────────────────────────────────────────────────────────┘
```

**A regra:** `infra` depende de `core`, nunca o contrário. O `core` declara **portas** (interfaces) e a `infra` as implementa:

| Porta (em `core`) | Implementação (em `infra`) |
|---|---|
| `SagaGateway` | `SagaRepositoryImpl` (MongoDB) |
| `MessagePublisher` | `OutboxMessagePublisher` (grava na outbox) |

O `core` (e o pacote `shared`) não importa nada do Spring, do MongoDB nem do RabbitMQ: só Java e as próprias classes do projeto.

---

## 3. Inicialização: o que o Spring monta

Ao subir, o Spring faz a varredura de pacotes a partir de `com.mecanicadm.saga_api` e cria estes beans, nesta lógica:

| Classe | O que cria ou habilita |
|---|---|
| `SagaApiApplication` | Ponto de entrada. `@SpringBootApplication` faz a varredura, e `@EnableRabbit` ativa os `@RabbitListener`. |
| `MongoConfig` | O `MongoTransactionManager` (`transactionManager`). Sem ele, o Spring Data MongoDB não participa de transações. É o `PlatformTransactionManager` usado por todo o resto. |
| `MessagingConfig` | Habilita o binding de `OutboxProperties` (`@EnableConfigurationProperties`). |
| `OutboxSchedulingConfig` | `@EnableScheduling`, condicionado a `messaging.outbox.scheduler.enabled` (padrão `true`). Os testes desligam, para a outbox não publicar sozinha. |
| `RabbitMQConfig` | O conversor de eventos (`eventMessageConverter`), a fábrica de listeners (`eventListenerContainerFactory`) e o `RabbitTemplate`. |
| `SagaConfiguration` | O `SagaOrchestrator` (`SagaFlow.standard()`) e o `ProcessSagaEventUseCase`, este envolvido por `TransactionalProxy`. |
| `SagaEventsConsumer` | Registra o listener nas duas filas de entrada. |
| `OutboxPublisher`, `OutboxStore`, `OutboxSequence`, `InboxStore`, `DistributedLock`, `OutboxMessagePublisher`, `RabbitOutboxMessageSender`, `MessageConsumerSupport`, `MessageJsonSerializer` | `@Component`, criados pela varredura. |
| `SagaMongoRepository` | Interface do Spring Data, implementada em tempo de execução. |
| `SagaRepositoryImpl` | `@Repository`, a implementação do `SagaGateway`. |

**Detalhe do `TransactionalProxy`.** O caso de uso é um objeto comum, sem anotações. A `SagaConfiguration` o embrulha com `TransactionalProxy.of(...)`, que usa `ProxyFactory` e um `TransactionInterceptor` com `MatchAlwaysTransactionAttributeSource`: **todo método público do caso de uso roda numa transação**, juntando-se à que o `MessageConsumer` já abriu (propagação `REQUIRED`).

**Detalhe do `RabbitMQConfig`.** A fábrica de listeners usa: ack **manual**, **1 consumidor** (concorrência 1 a 1), prefetch configurável (padrão 10) e `defaultRequeueRejected = false` (mensagem rejeitada não volta para a fila, vai para a DLQ).

---

## 4. A jornada de uma mensagem, método a método

Exemplo seguido: o `WorkOrderDiagnosedEvent` chega e o saga responde com o `GenerateBudgetIntegrationCommand`. A saga já existe, em `WORK_ORDER_OPEN`.

```
 ┌─ ENTRADA ─────────────────────────────────────────────────────────────────────┐
 │ 1  Broker entrega a mensagem (fila saga.workorder-events)                      │
 │ 2  RabbitMQConfig.eventMessageConverter   JSON ──▶ WorkOrderDiagnosedEvent     │
 │ 3  SagaEventsConsumer.onEvent                                                  │
 │ 4  MessageConsumer.consume ──▶ Envelope.of ──▶ validate ──▶ process           │
 └────────────────────────────────────────────────────────────────────────────────┘
 ┌─ TRANSAÇÃO MONGODB (abre em process, fecha no commit) ─────────────────────────┐
 │ 5  InboxStore.register                  grava o message-id no inbox            │
 │ 6  ProcessSagaEventUseCase.execute                                             │
 │ 7    SagaRepositoryImpl.findById        Mongo ──▶ SagaInstance                 │
 │ 8    SagaOrchestrator.handle                                                   │
 │ 9      SagaDefinition.ruleFor           acha a TransitionRule                  │
 │ 10     BudgetStep.requestBudget         muda o estado, devolve o command       │
 │ 11     SagaInstance.recordTransition    anota no histórico                     │
 │ 12   SagaRepositoryImpl.save            SagaInstance ──▶ SagaDocument ──▶ Mongo│
 │ 13   OutboxMessagePublisher.publish     grava o command na outbox (PENDING)    │
 └────────────────────────────────────────────────────────────────────────────────┘
 ┌─ DEPOIS DO COMMIT ─────────────────────────────────────────────────────────────┐
 │ 14 channel.basicAck                                                            │
 └────────────────────────────────────────────────────────────────────────────────┘
 ┌─ SAÍDA (assíncrona, a cada 1 s) ───────────────────────────────────────────────┐
 │ 15 OutboxPublisher.publishPending ──▶ DistributedLock.tryAcquire               │
 │ 16   OutboxStore.findPending                                                   │
 │ 17   RabbitOutboxMessageSender.send     publica em saga-output, espera confirm │
 │ 18   OutboxStore.markPublished                                                 │
 │ 19 Broker: saga-output ──▶ finance-input ──▶ fila budget                       │
 └────────────────────────────────────────────────────────────────────────────────┘
```

### Passo a passo

**1. O broker entrega a mensagem.** O container de listener (criado pela `eventListenerContainerFactory`) lê da fila e entrega a mensagem a um único thread.

**2. Conversão do JSON (`RabbitMQConfig.eventMessageConverter`).** É um `Jackson2JsonMessageConverter` com um `DefaultJackson2JavaTypeMapper` customizado:
- O identificador do tipo é o header `event-type`.
- O mapeamento do nome para a classe é montado a partir de `SagaEvent.class.getPermittedSubclasses()`: **qualquer evento novo no `permits` já é mapeado**.
- Se o header faltar ou o tipo for desconhecido, lança `MessageConversionException` e a mensagem vai para a DLQ.
- O `ObjectMapper` vem de `MessageJsonSerializer.newObjectMapper()`: módulo de datas Java, datas como texto ISO-8601 e `FAIL_ON_UNKNOWN_PROPERTIES` desligado (o JSON pode trazer campos extras sem quebrar nada).

**3. `SagaEventsConsumer.onEvent`.** Estende `MessageConsumer`. Tem **um único** `@RabbitHandler` que recebe `SagaEvent`, a interface de todos os eventos, então atende qualquer evento convertido. O `@RabbitListener` escuta as duas filas ao mesmo tempo (`saga.workorder-events` e `saga.finance-events`) com **um único consumer**. Ele só chama `consume(event.workOrderId(), event, message, channel, processSagaEventUseCase::execute)`.

**4. `MessageConsumer.consume`.** Controla o ciclo de vida da mensagem:
- Monta o `Envelope` (`message-id`, `event-type`, `workOrderId`). O `message-id` vem do header `message-id` e, se não houver, da propriedade AMQP `message_id`.
- `validate` exige `message-id` e `workOrderId`. Sem um deles, lança `InvalidMessageException`.
- `process` roda a transação (passos 5 a 13).
- Sucesso: log e `basicAck`.
- Qualquer exceção: `registerFailure` (grava `FAILED` no inbox, em outra transação) e `basicNack` sem requeue, o que manda para a DLQ.

**5. `process` e o inbox.** `process` usa o `TransactionTemplate` do `MessageConsumerSupport`, ligado ao `MongoTransactionManager`. Dentro da transação, a primeira coisa é `InboxStore.register(...)`, que devolve `1` (primeira vez, ou reprocessamento de `REJECTED`/`FAILED`) ou `0` (já `PROCESSED`). Com `0`, o resultado é `DUPLICATE` e o handler nem roda. Se o handler lançar uma `DomainExceptionCore` (como `SagaNotFound`), a transação é **desfeita** (inclusive o registro no inbox) e `reject` grava o `REJECTED` numa **segunda transação**, com ack.

**6. `ProcessSagaEventUseCase.execute`.** Implementa `VoidUseCase<SagaEvent>`.
- Existe a saga: `orchestrator.handle(saga, event)`.
- Não existe e o evento é `WorkOrderCreatedEvent`: `orchestrator.start(created)`, sem commands.
- Não existe e o evento é outro: lança `SagaExceptions.NotFound`.
- Depois: `gateway.save(saga)` e, para cada command, `publisher.publish(workOrderId, command)`.

**7. `SagaRepositoryImpl.findById`.** Delega ao `SagaMongoRepository`, recebe um `SagaDocument` e converte em `SagaInstance` com `SagaInstance.restore(...)`. O `core` nunca vê o `SagaDocument`.

**8 e 9. `SagaOrchestrator.handle` e `SagaDefinition.ruleFor`.** O orquestrador guarda o estado de origem. Se a saga já terminou, não decide nada. Senão, pede à `SagaDefinition` a **primeira regra** que casa com o evento (`eventType.isInstance(event)`) e com a guarda (`guard.test(saga)`). Sem regra que case, o evento é ignorado.

**10. A regra (`BudgetStep.requestBudget`).** Chama `saga.moveTo(AWAITING_BUDGET)` e devolve `GenerateBudgetIntegrationCommand.from(workOrderId, saga.getWorkOrder())`. O command leva o retrato da OS guardado na saga convertido para o contrato do Finance (`workOrderSnapshot`, com `totalPrice` calculado pela saga e `price` unitário nos materiais).

**11. `SagaInstance.recordTransition`.** Acrescenta ao histórico uma `SagaTransition` com o evento, o estado de origem, o estado de destino e os nomes dos commands emitidos.

**12. `SagaRepositoryImpl.save`.** Converte a `SagaInstance` em `SagaDocument` e chama `repository.save`. O campo `version` (`@Version`) dá o **lock otimista**: se outra instância alterou a saga no meio do caminho, o salvamento falha com `OptimisticLockingFailureException`.

**13. `OutboxMessagePublisher.publish`.** Marcado com `@Transactional(propagation = MANDATORY)`: exige que já exista uma transação em andamento, e falha se não houver. Ele serializa o command em JSON (`MessageJsonSerializer`), pega o próximo número de ordem (`OutboxSequence.next`) e insere um `OutboxDocument` com status `PENDING` (`OutboxStore.save`). O `event-type` da mensagem é `message.getClass().getSimpleName()`.

**14. Commit e ack.** A transação fecha: a saga, o inbox e a outbox ficam gravados **juntos**. Só então o `consume` dá o `basicAck`.

**15 a 18. A publicação.** A cada segundo (`@Scheduled`), o `OutboxPublisher.publishPending`:
1. Pede o lock `outbox-publisher` ao `DistributedLock`. Sem o lock, outra instância está publicando e esta desiste.
2. Lê as mensagens pendentes em ordem de `sequence`, até o tamanho do lote.
3. Para cada uma, `RabbitOutboxMessageSender.send` monta a mensagem (corpo JSON, `PERSISTENT`, headers `message-id` e `event-type`) e publica na exchange `saga-output` com *publisher confirm*, esperando a confirmação do broker até o timeout. Depois `OutboxStore.markPublished`.
4. Falha ao publicar: a linha vira `FAILED` com o motivo e **não é reenviada**.
5. Libera o lock.

Uma segunda rotina (`cleanPublished`, `@Scheduled` por cron) apaga as mensagens `PUBLISHED` mais antigas que o período de retenção.

**19. O roteamento.** Já no broker, a `saga-output` repassa para a `workorder-input` e a `finance-input`. Cada uma só entrega na fila cujo bind casa com o `event-type`. O `GenerateBudgetIntegrationCommand` chega na fila `budget` do Finance.

---

## 5. Catálogo de arquivos

Caminho base: `src/main/java/com/mecanicadm/saga_api`. A coluna **Passo** remete à numeração da seção 4. "Config" significa que a classe participa da inicialização (seção 3).

### 5.1 `SagaApiApplication`

| Arquivo | O que é e como funciona | Passo |
|---|---|---|
| `SagaApiApplication.java` | Classe `main`. `@SpringBootApplication` e `@EnableRabbit`. | Config |

### 5.2 `core/saga/event`: eventos que o saga recebe

São `record`s imutáveis. O nome da classe é o `event-type` da mensagem. Todos implementam `SagaEvent` e expõem `workOrderId()`.

| Arquivo | O que é | Passo |
|---|---|---|
| `SagaEvent.java` | `sealed interface` que lista (`permits`) todos os eventos. É a **fonte da verdade**: o conversor de mensagens e a `SagaDefinition` usam `SagaEvent.class.getPermittedSubclasses()` para se montarem. Declara `UUID workOrderId()`. | 2, 3, 9 |
| `WorkOrderCreatedEvent.java`, `WorkOrderUpdatedEvent.java` | Os dados completos da OS: `client`, `vehicle`, `description`, `status`, datas de execução, `laborItems` e `materialItems`. O método `snapshot()` converte o evento em `WorkOrderSnapshot`. | 2, 6 |
| `WorkOrderDiagnosedEvent.java`, `WorkOrderExecutionStartedEvent.java`, `WorkOrderExecutionFinishedEvent.java`, `WorkOrderCanceledEvent.java`, `WorkOrderVehicleDeliveredEvent.java` | Só o `workOrderId`. | 2 |
| `BudgetGeneratedEvent.java` | `workOrderId`, `totalPrice`. | 2 |
| `BudgetUpdatedEvent.java` | `workOrderId`, `totalPrice`, `observation`. | 2 |
| `BudgetChangesRequestedEvent.java` | `workOrderId`, `observation`. | 2 |
| `PaymentLinkGeneratedEvent.java` | `workOrderId`, `paymentLink`. | 2 |
| `PaymentFailedEvent.java` | `workOrderId`, `reason`. | 2 |
| `BudgetSentForDecisionEvent.java`, `BudgetSentForDecisionFailedEvent.java`, `BudgetApprovedEvent.java`, `BudgetRejectedEvent.java`, `BudgetCanceledEvent.java`, `BudgetExpiredEvent.java`, `PaymentLinkGenerationFailedEvent.java`, `PaymentConfirmedEvent.java` | Só o `workOrderId`. | 2 |

### 5.3 `core/saga/command`: commands que o saga envia

São `record`s. O nome da classe é o `event-type` da mensagem publicada. Todos implementam `SagaCommand` e expõem `workOrderId()`.

| Arquivo | Destino | Campos | Passo |
|---|---|---|---|
| `SagaCommand.java` | (interface) | `sealed interface` com `permits` para os 7 commands. | 13 |
| `GenerateBudgetIntegrationCommand.java` | Finance | `workOrderId`, `workOrderSnapshot` (retrato da OS no formato do Finance, `BudgetWorkOrderSnapshot`) | 10, 13 |
| `CancelBudgetIntegrationCommand.java` | Finance | `workOrderId` | 13 |
| `GeneratePaymentIntegrationCommand.java` | Finance | `workOrderId` | 13 |
| `AuthorizeWorkOrderIntegrationCommand.java` | Workorder | `workOrderId` | 13 |
| `CancelWorkOrderIntegrationCommand.java` | Workorder | `workOrderId` | 13 |
| `ChangesRequestedWorkOrderIntegrationCommand.java` | Workorder | `workOrderId`, `observation` | 13 |
| `ReleaseWorkOrderVehicleIntegrationCommand.java` | Workorder | `workOrderId` | 13 |

### 5.4 `core/saga/domain`: o agregado e seus tipos

| Arquivo | O que é e como funciona | Passo |
|---|---|---|
| `SagaInstance.java` | O **agregado**. Guarda `workOrderId`, `state`, `pausedFrom`, retrato da OS, `budgetTotal`, `pendingCompensations`, `pauseReason`, `paymentLink`, `version`, datas e o `history`. Oferece **operações de intenção** (`moveTo`, `pause`, `replaceWorkOrder`, `registerBudget`, `discardBudget`, `requestBudgetCancellation`, `acknowledgeBudgetCancellation`, `registerPaymentLink`, `recordTransition`) e **consultas** (`isIn`, `isPausedFrom`, `isInStep`, `currentStep`, `hasActiveBudget`, `isTerminal`). `start(WorkOrderCreatedEvent)` cria a saga em `WORK_ORDER_OPEN`, e `restore(...)` a reconstrói a partir do banco. **Não decide nada**: quem decide são as regras. | 6, 7, 10, 11 |
| `SagaState.java` | Enum dos 14 estados. Cada um aponta para a sua `SagaStep` (`step()`), e `CANCELING`, `PAUSED`, `COMPLETED` e `CANCELED` não pertencem a nenhuma etapa. `isTerminal()` vale para `COMPLETED` e `CANCELED`. | 8 |
| `SagaStep.java` | Enum das 6 etapas, em ordem: `REGISTRATION`, `BUDGET`, `CUSTOMER_DECISION`, `EXECUTION`, `PAYMENT`, `DELIVERY`. `isAtLeast(outra)` compara a posição. | 8 |
| `SagaTransition.java` | `record` de uma linha do histórico: evento, estado de origem (`null` na criação), estado de destino, commands e instante. | 11 |
| `WorkOrderSnapshot.java` | O retrato da OS guardado na saga (cliente, veículo, itens). `totalPrice()` soma o `price` de cada mão de obra com o `totalPrice` de cada material. | 6, 10 |
| `ClientSnapshot.java`, `VehicleSnapshot.java`, `LaborItemSnapshot.java`, `MaterialItemSnapshot.java` | Partes do retrato, como `record`s. | 2 |
| `port/SagaGateway.java` | A porta de persistência: `findById(UUID)` e `save(SagaInstance)`. | 6, 7, 12 |

### 5.5 `core/saga/engine`: o motor (genérico)

O motor **não conhece nenhum step de negócio**. Para mudar o fluxo você mexe em `flow/`, nunca aqui.

| Arquivo | O que é e como funciona | Passo |
|---|---|---|
| `SagaOrchestrator.java` | O ponto de entrada do motor. `handle(saga, evento)` guarda o estado de origem, executa a regra que casa, registra a transição, loga a pausa (`SAGA_PAUSED`) e devolve os commands. Com a saga encerrada, só valem as regras marcadas com `afterTermination`. `start(evento)` cria a saga. Recebe a `SagaDefinition` pronta. | 8 |
| `SagaDefinition.java` | Recebe a lista de `SagaFlowStep`, junta as regras por tipo de evento e, na criação, **falha se algum evento do `permits` ficar sem regra**. `ruleFor(evento, saga)` devolve a primeira regra que casa, e `terminalRuleFor` a primeira que casa entre as válidas para saga encerrada. | 9 |
| `SagaFlowStep.java` | O contrato de uma etapa: `rules()` (as regras que reagem aos eventos) e `compensations()` (como desfazer o que o step fez, vazio por padrão). | 9 |
| `TransitionRule.java` | `record(Class eventType, Predicate guard, BiFunction action, boolean handlesTerminal)`. `TransitionRule.on(...)` cria a regra comum e `TransitionRule.afterTermination(...)` a que também vale com a saga encerrada. | 9, 10 |
| `Compensation.java` | `record(SagaStep step, Predicate applies, Function command)`: a etapa cujo efeito é desfeito, se há algo a desfazer no estado atual e o command que desfaz. | 10 |
| `CompensationPlanner.java` | Junta as compensações de todos os steps (`register(steps)`, feito uma vez na montagem) e, em `compensateFrom(etapa, saga, motivo)`, devolve os commands da etapa mais avançada para a mais antiga, **registra cada um como pendente na saga** e loga `SAGA_COMPENSATED`. | 10 |
| `Guards.java` | As guardas prontas: `always()`, `inState(...)`, `inStep(...)`, `pausedFrom(...)` e `awaitingCompensation(command)`. | 9 |

### 5.6 `core/saga/flow` e `flow/steps`: o fluxo de negócio

Cada step implementa `SagaFlowStep`: as regras do seu assunto, **na ordem em que valem**, e, quando produz efeito em outro serviço, a **sua própria compensação**. `SagaFlow.standard()` monta a lista de steps, o `CompensationPlanner` e o `SagaOrchestrator`.

| Arquivo | Regras | Compensação própria | Passo |
|---|---|---|---|
| `RegistrationStep.java` | `WorkOrderCreatedEvent` (saga já existe: não faz nada). `WorkOrderUpdatedEvent` nas etapas `BUDGET` e `CUSTOMER_DECISION`: atualiza o retrato, compensa o orçamento, descarta o total e reabre a saga. Em qualquer outro caso: só atualiza o retrato. | `CancelWorkOrder` (etapa `REGISTRATION`): desfaz a abertura, a autorização e a execução da OS | 10 |
| `BudgetStep.java` | `WorkOrderDiagnosedEvent` em `WORK_ORDER_OPEN`: vai para `AWAITING_BUDGET` e emite `GenerateBudget` com o retrato da OS. `BudgetGeneratedEvent`: guarda o total. `BudgetUpdatedEvent`: atualiza o total. `BudgetSentForDecisionEvent`: vai para `AWAITING_CUSTOMER_DECISION` (também retoma a pausa de envio). `BudgetSentForDecisionFailedEvent`: pausa. | `CancelBudget` (etapa `BUDGET`), quando há orçamento ativo | 10 |
| `CustomerDecisionStep.java` | `BudgetApprovedEvent`: emite `Authorize`. `BudgetRejectedEvent` e `BudgetExpiredEvent`: emitem `CancelWorkOrder` e vão para `CANCELING`. `BudgetCanceledEvent`: se a saga espera a confirmação do `CancelBudget`, só a conclui; senão, trata como expiração. `BudgetChangesRequestedEvent`: emite `ChangesRequestedWorkOrder`. | Nenhuma | 10 |
| `ExecutionStep.java` | `WorkOrderExecutionStartedEvent`: vai para `IN_EXECUTION`. `WorkOrderExecutionFinishedEvent`: emite `GeneratePayment` e vai para `AWAITING_PAYMENT_LINK`. | Nenhuma (é desfeita pelo `CancelWorkOrder`) | 10 |
| `PaymentStep.java` | `PaymentLinkGeneratedEvent`: guarda o link (também retoma a pausa do link). `PaymentLinkGenerationFailedEvent`: pausa. `PaymentConfirmedEvent`: emite `ReleaseWorkOrderVehicle` (também retoma a pausa de pagamento). `PaymentFailedEvent`: pausa. `PaymentCanceledEvent` e `PaymentRefundedEvent`: concluem a compensação correspondente. Com a saga em `CANCELING` ou `CANCELED`: `PaymentConfirmedEvent` gera o estorno e `PaymentLinkGeneratedEvent` o cancelamento do link. | `CancelPayment` (etapa `PAYMENT`, pagamento pedido e não pago) e `RefundPayment` (etapa `DELIVERY`, pagamento confirmado) | 10 |
| `DeliveryStep.java` | `WorkOrderVehicleDeliveredEvent`: vai para `COMPLETED`. | Nenhuma | 10 |
| `CancellationStep.java` | `WorkOrderCanceledEvent` com a saga cancelando ou pausada enquanto cancelava: conclui o `CancelWorkOrder`. Em qualquer outro caso (a OS foi cancelada por outra pessoa): compensa a partir do orçamento e vai para `CANCELING`. Nos dois casos, só encerra em `CANCELED` quando não resta compensação pendente. | Nenhuma | 10 |
| `CommandRejectionStep.java` | `WorkOrderCommandRejectedEvent` e `FinanceCommandRejectedEvent`: política por estado (compensa e cancela a OS, ou pausa). A recusa do `CancelBudget` ou do `CancelPayment` conclui a compensação (não havia o que cancelar). A recusa do `RefundPayment` pausa a saga e loga `SAGA_COMPENSATION_FAILED`. | Nenhuma | 10 |
| `UnexpectedEventStep.java` | Evento à frente do estado da saga: pausa com o motivo `UnexpectedEvent`. Última da montagem. | Nenhuma | 10 |
| `SagaFlow.java` | `standard()`: cria o `CompensationPlanner`, a lista de steps na ordem, registra as compensações e devolve o `SagaOrchestrator`. | | 8 |

### 5.7 Compensação e confirmação

Cada step declara a compensação do que fez (`SagaFlowStep.compensations()`), e o `CompensationPlanner` as junta. Quem decide **quando** compensar são as regras: alteração da OS, cancelamento da OS e recusa de command.

O planner registra cada command de compensação como **pendente** na saga (`SagaInstance.pendingCompensations`, gravado no MongoDB). A saga em `CANCELING` só vai para `CANCELED` quando a lista fica vazia, e cada confirmação a esvazia um pouco:

| Compensação pendente | Confirma |
|---|---|
| `CancelWorkOrder` | `WorkOrderCanceledEvent` |
| `CancelBudget` | `BudgetCanceledEvent` (ou a recusa por não haver orçamento) |
| `CancelPayment` | `PaymentCanceledEvent` (ou a recusa por não haver link) |
| `RefundPayment` | `PaymentRefundedEvent`. A recusa **pausa** a saga |

### 5.8 `core/saga/usecase` e `core/saga/exception`

| Arquivo | O que é e como funciona | Passo |
|---|---|---|
| `usecase/ProcessSagaEventUseCase.java` | O único caso de uso. Orquestra: busca a saga, chama o orquestrador (ou cria a saga), salva e publica os commands. Depende só das portas `SagaGateway` e `MessagePublisher` e do `SagaOrchestrator`. | 6 |
| `exception/SagaExceptions.java` | `SagaExceptions.NotFound` (chave `saga.not.found`, status 404). Estende `DomainExceptionCore`, e por isso é tratada como **recusa de negócio** pelo `MessageConsumer`. | 5, 6 |

### 5.9 `core/shared` e `shared`

| Arquivo | O que é e como funciona | Passo |
|---|---|---|
| `core/shared/messaging/MessagePublisher.java` | A porta de publicação: `publish(UUID workOrderId, Object message)`. O `workOrderId` vira o `aggregate_id` da outbox. | 13 |
| `shared/usecase/UseCase.java`, `VoidUseCase.java` | Interfaces dos casos de uso (com e sem retorno). | 6 |
| `shared/exception/DomainExceptionCore.java` | Base das exceções de regra de negócio: chave de mensagem, status e argumentos. O `MessageConsumer` a captura e transforma em `REJECTED`. | 5 |
| `shared/exception/TechnicalException.java` | Exceção de falha técnica (código e argumentos). Usada na serialização da outbox. | 13 |

### 5.10 `infra/features/saga`: a feature do saga

| Arquivo | O que é e como funciona | Passo |
|---|---|---|
| `amqp/SagaEventsConsumer.java` | O consumer. Estende `MessageConsumer`, escuta as duas filas e delega ao caso de uso. | 3 |
| `config/SagaConfiguration.java` | `@Configuration` que cria o `SagaOrchestrator` e o `ProcessSagaEventUseCase`, este envolvido por `TransactionalProxy`. | Config |
| `persistence/SagaDocument.java` | O documento da coleção `saga_instances`. `@Id` é o `workOrderId`, `@Version` é o lock otimista, `@Indexed` no `state`. Guarda o retrato e o histórico embutidos. | 7, 12 |
| `persistence/SagaMongoRepository.java` | `MongoRepository<SagaDocument, UUID>` do Spring Data, sem métodos próprios. | 7, 12 |
| `persistence/SagaRepositoryImpl.java` | Implementa o `SagaGateway`. Converte `SagaDocument` em `SagaInstance` (`restore`) e o contrário. É a **única** classe que conhece as duas representações. | 7, 12 |

### 5.11 `infra/messaging`: a mensageria

#### Consumo

| Arquivo | O que é e como funciona | Passo |
|---|---|---|
| `consumer/MessageConsumer.java` | A **classe base** de todo consumer. Controla inbox, transação, ack, recusa, falha e DLQ. O filho só informa qual caso de uso chamar. Contém o `record Envelope(messageId, eventType, workOrderId)`. | 4, 5, 14 |
| `consumer/MessageConsumerSupport.java` | Agrega o `InboxStore` e o `TransactionTemplate` (criado com o `PlatformTransactionManager`, que é o do MongoDB). | 5 |
| `consumer/ProcessingResult.java` | Enum do resultado: `PROCESSED`, `DUPLICATE` e `REJECTED`. | 5 |
| `consumer/InvalidMessageException.java` | Lançada quando a mensagem vem sem `message-id` ou sem `workOrderId`. Cai no ramo de falha e a mensagem vai para a DLQ. | 4 |
| `config/RabbitMQConfig.java` | O conversor pelo `event-type`, a fábrica de listeners e o `RabbitTemplate`. | 2 |

#### Inbox

| Arquivo | O que é e como funciona | Passo |
|---|---|---|
| `inbox/InboxStore.java` | Opera na coleção `inbox`. `register(...)` insere a mensagem nova, devolve `0` se já estava `PROCESSED`, e atualiza (devolvendo `1`) se estava `REJECTED` ou `FAILED`, mudando também o tipo e a OS. `registerFailure(...)` grava `FAILED` com o motivo, sem sobrescrever um `PROCESSED`. | 5 |
| `inbox/InboxDocument.java` | O documento: `messageId` (`_id`), `messageType`, `workOrderId`, `status`, `error`, `processedAt` (indexado). | 5 |
| `inbox/InboxStatus.java` | Enum: `PROCESSED`, `REJECTED`, `FAILED`. | 5 |

#### Outbox e publicação

| Arquivo | O que é e como funciona | Passo |
|---|---|---|
| `outbox/OutboxMessagePublisher.java` | Implementa o `MessagePublisher`. Serializa, numera e grava na outbox. `@Transactional(MANDATORY)`. | 13 |
| `outbox/OutboxDocument.java` | O documento da coleção `outbox`: `_id`, `sequence` (índice único), `aggregateId`, `type`, `payload` (JSON como texto), `occurredAt`, `status`, `publishedAt`, `lastError`. Índice composto `status` + `sequence`. | 13, 16 |
| `outbox/OutboxStore.java` | Opera na coleção: `save`, `findPending(limite)`, `findByAggregateId`, `markPublished`, `markFailed` e `deletePublishedBefore`. | 13, 16, 18 |
| `outbox/OutboxSequence.java` | Gera o número de ordem: microssegundos do relógio, garantidamente crescente dentro da instância (`max(anterior + 1, agora)`). Evita um contador compartilhado no banco, que causaria conflito entre transações. | 13 |
| `outbox/OutboxPublisher.java` | O agendador. `publishPending` (a cada segundo) e `cleanPublished` (cron). Só publica com o lock. | 15 a 18 |
| `outbox/RabbitOutboxMessageSender.java` | Publica uma linha da outbox no RabbitMQ: corpo, headers `message-id` e `event-type`, modo persistente, *publisher confirm* com timeout. As constantes `EVENT_TYPE_HEADER` e `MESSAGE_ID_HEADER` valem para consumo e publicação. | 17 |
| `outbox/OutboxProperties.java` | Propriedades de `messaging.outbox`: `batchSize` (50), `confirmTimeoutMs` (5000), `retentionDays` (7). | 15 |
| `outbox/OutboxStatus.java` | Enum: `PENDING`, `PUBLISHED`, `FAILED`. | 16 |
| `lock/DistributedLock.java` | Lock por *lease* na coleção `locks`. `tryAcquire(nome, leaseMs)` usa um `findAndModify` com `upsert`: ou adquire, ou a chave duplicada nega. `release` zera o lease, só se o dono for esta instância. O lease expira sozinho se a instância cair. | 15 |
| `serialization/MessageJsonSerializer.java` | Cria o `ObjectMapper` do projeto e serializa os commands. | 2, 13 |
| `config/MessagingConfig.java`, `config/OutboxSchedulingConfig.java` | Configuração (seção 3). | Config |

### 5.12 `infra/config`

| Arquivo | O que é e como funciona |
|---|---|
| `MongoConfig.java` | O `MongoTransactionManager`. |
| `TransactionalProxy.java` | Embrulha um objeto comum num proxy em que todo método público roda numa transação. |

### 5.13 Recursos e raiz do projeto

| Arquivo | Para que serve |
|---|---|
| `pom.xml` | Dependências: web, actuator, Prometheus, AMQP, Spring Data MongoDB, `logback13` (New Relic) e as de teste (Testcontainers para MongoDB e RabbitMQ). |
| `Dockerfile` | Compila com Maven e roda numa imagem Corretto 21 com o agente do New Relic. |
| `docker-compose.yml` | Sobe o MongoDB (como replica set de um nó, iniciado pelo healthcheck) e a API (porta 8081). O broker é externo. |
| `docker-compose.ci.yml` | Troca a imagem da API na integração contínua. |
| `.env`, `.env.example` | Variáveis de ambiente (seção 9). O `.env` não vai para o git. |
| `src/main/resources/application.yaml` | Configuração base: MongoDB, RabbitMQ, filas, exchange, outbox, actuator. |
| `src/main/resources/application-local.yaml` | O perfil `local`: URI do MongoDB para rodar fora do Docker. |
| `src/main/resources/logback-spring.xml` | Log em JSON no formato do New Relic. |
| `src/main/resources/i18n/messages*.properties` | Textos das mensagens de erro (português, inglês e espanhol). |
| `messaging/rabbitmq-definitions.json` | A topologia de **todos** os serviços (exchanges, filas, binds), para importar no broker. |
| `docs/` | A documentação (`apoio/` guarda este material). |
| `sonar-project.properties` | Configuração do Sonar. |
| `mvnw`, `mvnw.cmd`, `.mvn/` | O Maven Wrapper. |

---

## 6. Modelo de dados (MongoDB)

### `saga_instances`

```
{
  _id: <workOrderId>,
  version: 3,
  state: "AWAITING_CUSTOMER_DECISION",
  pausedFrom: null,
  workOrder: {
    client:   { id, name, email, document, phone },
    vehicle:  { licensePlate, model, brand, modelYear },
    description, status, executionStartAt, executionEndAt,
    laborItems:    [ { id, laborId, name, price, status, executionStartAt, executionEndAt } ],
    materialItems: [ { id, materialId, name, brand, description, type, unitPrice, quantity, totalPrice } ]
  },
  budgetTotal: "720.00",
  pendingCompensations: [],
  pauseReason: null,
  paymentLink: null,
  createdAt, updatedAt,
  history: [
    { eventType: "WorkOrderCreatedEvent", fromState: null, toState: "WORK_ORDER_OPEN", commands: [], occurredAt },
    { eventType: "WorkOrderDiagnosedEvent", fromState: "WORK_ORDER_OPEN", toState: "AWAITING_BUDGET",
      commands: ["GenerateBudgetIntegrationCommand"], occurredAt }
  ]
}
```

Índice: `state`. O `history` cresce só uma entrada por evento (algumas dezenas por OS), por isso fica embutido.

### `inbox`

`{ _id: <message-id>, messageType, workOrderId, status, error, processedAt }`. Índice em `processedAt`.

### `outbox`

`{ _id: <message-id gerado>, sequence, aggregateId, type, payload, occurredAt, status, publishedAt, lastError }`. Índices: `sequence` (único) e `status` + `sequence`.

### `locks`

`{ _id: "outbox-publisher", lockedUntil, owner }`.

As coleções e os índices são criados sozinhos (`auto-index-creation: true`). O UUID é gravado no formato `standard` (`uuid-representation: standard`).

---

## 7. Contratos de mensagem

### Headers

| Header | Valor | Quem lê |
|---|---|---|
| `event-type` | O nome simples da classe (ex.: `WorkOrderDiagnosedEvent`). **É o que roteia** nas exchanges `headers` e escolhe a classe no conversor. | Broker e saga |
| `message-id` | UUID da mensagem. É a chave do inbox. O consumer aceita também a propriedade AMQP `message_id`, como fallback. | Saga |

O `messageId`, o `eventType` e a data de ocorrência **não vão no corpo**.

### Exemplo de evento recebido (`WorkOrderCreatedEvent`)

```json
{
  "workOrderId": "a06ed083-93b2-4313-b9bc-fc3626b6b6b8",
  "client": { "id": "8b3f81e5-e118-4e31-8979-994cba5256e4", "name": "Deliveries Express",
              "email": "express@deliver.com", "document": "11222333000181", "phone": "48999999005" },
  "vehicle": { "licensePlate": "ABC-1234", "model": "Volvo FH 460", "brand": "Volvo", "modelYear": 2020 },
  "description": "Revisão", "status": "RECEIVED", "executionStartAt": null, "executionEndAt": null,
  "laborItems": [ { "id": "267ef2a5-0580-46ec-bae7-0dccc29c8156", "laborId": "e8281144-411a-4d37-97d8-1e4eb1e79391",
                    "name": "Limpeza de Carbono", "price": 300.00, "status": "AWAITING_EXECUTION",
                    "executionStartAt": null, "executionEndAt": null } ],
  "materialItems": [ { "id": "11e2c32c-5ae2-42e0-a51a-110183c6eb90", "materialId": "5c5450e1-236b-43d9-95a7-96a1a1f73b15",
                       "name": "Filtro de Óleo", "brand": "Mann", "description": "Filtro", "type": "PART",
                       "unitPrice": 45.00, "quantity": 2, "totalPrice": 90.00 } ]
}
```

### Exemplos dos demais

```json
{ "workOrderId": "..." }                                          // eventos só com o id
{ "workOrderId": "...", "totalPrice": 720.00 }                    // BudgetGeneratedEvent e GenerateBudgetIntegrationCommand
{ "workOrderId": "...", "observation": "Não trocar o filtro" }    // BudgetChangesRequestedEvent e ChangesRequestedWorkOrderIntegrationCommand
{ "workOrderId": "...", "paymentLink": "https://..." }            // PaymentLinkGeneratedEvent
{ "workOrderId": "...", "reason": "cartão recusado" }             // PaymentFailedEvent
```

O JSON pode trazer campos a mais (`messageId`, `ocurredAt`, `paymentId`...). Eles são ignorados.

---

## 8. Transações, idempotência e concorrência

| Garantia | Como é obtida |
|---|---|
| **Atomicidade** entre saga, inbox e outbox | Uma transação do MongoDB (`MongoTransactionManager`) aberta no `MessageConsumer.process` e usada por tudo o que vem depois. |
| **Idempotência** do consumo | O `message-id` no inbox. `PROCESSED` é duplicata. A gravação do inbox está **dentro** da transação, então uma falha desfaz o registro junto com tudo. |
| **Nenhum command se perde** | O command só existe se a transação deu certo, e a outbox o guarda até o broker confirmar. O ack vem depois do commit. |
| **Entrega pelo menos uma vez** | Se o saga cair entre a confirmação do broker e o `markPublished`, o command é enviado de novo. Quem recebe deduplica pelo `message-id`. |
| **Ordem dos commands** | A outbox é lida por `sequence`, e só uma instância publica por vez (`DistributedLock`). |
| **Evento fora de ordem** | Evento de OS sem saga é recusado (`REJECTED`) e pode ser reprocessado depois. Evento que não se aplica ao estado é ignorado, mas fica no histórico. |
| **Concorrência na mesma saga** | `@Version` (lock otimista). Com um único consumer por instância, o conflito só ocorre entre instâncias, e nesse caso a mensagem vai para a DLQ. |
| **Duas filas, um consumer** | Um único `@RabbitListener` escuta as duas filas com concorrência 1, então eventos da Workorder e do Finance para a mesma OS não são processados em paralelo. |

---

## 9. Configuração e infraestrutura

### Variáveis de ambiente

| Variável | Padrão | Efeito |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/saga_db?directConnection=true` | Conexão com o MongoDB. Deve apontar para um replica set. |
| `SPRING_PROFILES_ACTIVE` | `default` | Perfil ativo (`local` usa `application-local.yaml`). |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` | `localhost`, `5672`, `guest`, `guest`, `/` | Conexão com o broker. |
| `RABBITMQ_SSL_ENABLED` | `false` | TLS (use `true` e a porta `5671` no CloudAMQP). |
| `CONSUMER_PREFETCH` | `10` | Mensagens que o consumer recebe adiantadas. |
| `OUTBOX_SCHEDULER_ENABLED` | `true` | Liga a publicação e a limpeza da outbox. |
| `OUTBOX_PUBLISHER_INTERVAL_MS` | `1000` | Intervalo entre as rodadas de publicação. |
| `OUTBOX_BATCH_SIZE` | `50` | Mensagens por rodada. |
| `OUTBOX_CONFIRM_TIMEOUT_MS` | `5000` | Espera pela confirmação do broker. |
| `OUTBOX_RETENTION_DAYS` | `7` | Dias que as mensagens publicadas ficam antes de serem apagadas. |
| `OUTBOX_CLEANUP_CRON` | `0 0 3 * * *` | Quando a limpeza roda. |

### Nomes de filas e exchange (`application.yaml`)

`messaging.queue.workorder-events` = `saga.workorder-events`, `messaging.queue.finance-events` = `saga.finance-events`, `messaging.exchange.saga-output` = `saga-output`.

### Topologia do RabbitMQ

Veja a seção 7 de [como-o-saga-funciona.md](como-o-saga-funciona.md) e o arquivo `messaging/rabbitmq-definitions.json`. Para o broker na nuvem, importe esse arquivo no painel do RabbitMQ (se o vhost não for `/`, escolha o vhost na importação).

---

## 10. Testes

Os testes ficam em `src/test/java/com/mecanicadm/saga_api`. `mvn test` roda os unitários, e `mvn verify` inclui os de integração (que exigem Docker).

### Unitários (sem banco e sem broker)

| Arquivo | O que cobre |
|---|---|
| `core/saga/flow/SagaOrchestratorTest` | O fluxo feliz, os 8 cenários de negócio, a pausa e a retomada precisas, a compensação ao cancelar pausada, eventos ignorados e estados terminais. |
| `core/saga/engine/SagaDefinitionTest` | O motor sozinho, com steps de mentira: a montagem falha listando os eventos sem regra, vale a primeira regra que casa e, com a saga encerrada, só as regras `afterTermination`. |
| `core/saga/flow/SagaFlowTest` | O fluxo padrão cobre todos os eventos, e cada step com efeito declara a sua compensação. |
| `core/saga/engine/CompensationPlannerTest` | O planner sozinho: ordem da etapa mais avançada para a mais antiga, etapa inicial, `applies` e o log. |
| `core/saga/flow/CompensationConfirmationTest` | A saga só encerra o cancelamento quando todas as compensações são confirmadas, em qualquer ordem, e a recusa do estorno pausa. |
| `core/saga/flow/PaymentCompensationTest` | As compensações de link e de estorno, o pagamento que chega depois do cancelamento e a falha do estorno. |
| `core/saga/flow/CommandRejectionTest`, `UnexpectedEventTest` | A política de recusa de command e a pausa por evento inesperado. |
| `core/saga/usecase/ProcessSagaEventUseCaseTest` | Criação da saga, recusa de evento sem saga, salvamento e publicação dos commands (com `Mockito`). |
| `infra/messaging/config/RabbitMQConfigTest` | Conversão do JSON de cada evento pelo `event-type`, leitura do `workOrderId` e de campos extras, recusa de tipo desconhecido, e um bind na topologia para cada evento. |

### Integração (MongoDB e RabbitMQ em containers)

| Arquivo | O que cobre |
|---|---|
| `infra/features/saga/SagaFlowIT` | O caso de uso contra o MongoDB: persistência do retrato, commands na outbox em ordem, histórico, renegociação de orçamento, `SagaNotFound`, idempotência do `WorkOrderCreatedEvent`, lock otimista e **rollback** da saga e da outbox juntas. |
| `infra/features/saga/amqp/SagaMessagingIT` | O ponto a ponto com o broker real e a topologia consolidada: evento entra, saga muda, command sai e chega na fila certa do outro serviço. Também cobre `REJECTED` sem DLQ, reprocessamento do mesmo `message-id` e DLQ para mensagem ilegível. |
| `infra/messaging/inbox/InboxStoreIT` | As regras do inbox: duplicata, falha, reprocessamento de `FAILED` e de `REJECTED`, e `PROCESSED` que não é sobrescrito. |
| `infra/messaging/lock/DistributedLockIT` | O lock: uma vez só, liberação, expiração do lease e locks independentes. |

### Apoio

| Arquivo | O que é |
|---|---|
| `testutils/AbstractIntegrationTest` | Sobe o MongoDB (replica set) em container, aponta o Spring para ele e limpa as coleções. |
| `testutils/SagaEvents` | Fábrica dos eventos `WorkOrderCreatedEvent` (total 720,00) e `WorkOrderUpdatedEvent` (total 430,00) usados nos testes. |
| `src/test/resources/application-test.yaml` | Desliga o agendador da outbox nos testes. |
