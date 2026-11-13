# Regras do saga: guia para quem vem do modelo "orquestrador por etapas"

> **Material de apoio.** Explicação técnica escrita durante o desenvolvimento; pode estar desatualizada em detalhes. A referência é o [README](../../README.md), que descreve o que foi entregue; as decisões estão nos [ADRs](../adr/) e [RFCs](../rfc/).

Este guia é para quem está acostumado com um orquestrador em que cada etapa tem um enum (com configuração como o status exigido da entidade e a sequência) e um usecase com `definirEtapa`, `execute` e `compensar`. Aqui o saga funciona de outro jeito. Este documento explica a diferença e como ler as regras.

Para a visão completa do projeto, veja [como-o-saga-funciona.md](como-o-saga-funciona.md).

---

## 1. A diferença central

No modelo por etapas, o orquestrador **percorre uma sequência**, normalmente em ordem.

Aqui o modelo é **orientado a eventos**: não existe sequência fixa. O saga é uma máquina de estados e reage ao que acontece. Cada regra diz: "se chegar o evento X e a saga estiver no estado Y, faça Z".

| Modelo por etapas | Este projeto |
|---|---|
| Enum da etapa com a configuração | `SagaState` (estado exato) e `SagaStep` (agrupa estados do mesmo assunto) |
| Status exigido da entidade para executar | **Guarda** (`inState(...)`, `pausedFrom(...)` etc.) |
| Sequência definida no enum | Não há. A ordem vem de "qual evento chegou" e "em que estado estou" |
| `execute` do usecase | A **ação** da regra: muda o estado e devolve commands |
| `compensar` do usecase | `Compensation` (hoje só existe a de orçamento) |
| Orquestrador chama a etapa | Orquestrador recebe um **evento** e procura a regra que casa |
| Execução síncrona | Assíncrona: o saga emite um command e só segue quando o outro serviço responde com um evento |

---

## 2. A regra: `TransitionRule`

Uma regra tem três partes (`core/saga/engine/TransitionRule.java`):

```java
TransitionRule.on(
    BudgetApprovedEvent.class,                     // 1. gatilho: qual evento
    inState(SagaState.AWAITING_CUSTOMER_DECISION), // 2. guarda: em que estado vale
    this::authorizeWorkOrder);                     // 3. ação

private List<SagaCommand> authorizeWorkOrder(SagaInstance saga, BudgetApprovedEvent e) {
    saga.moveTo(SagaState.AWAITING_EXECUTION);                      // muda o estado
    return List.of(new AuthorizeWorkOrderIntegrationCommand(...));  // pede algo a outro serviço
}
```

- A **guarda** faz o papel do "status da entidade para poder executar".
- A **ação** faz o papel do `execute`. Ela muda o estado da saga e devolve a lista de commands a enviar (pode ser vazia).

Não há uma classe por etapa com três métodos. A etapa é só uma lista de regras.

---

## 3. Como as peças se encaixam

1. **`SagaFlowStep`** é o contrato de um step. Cada step (`BudgetStep`, `CustomerDecisionStep`, `PaymentStep`...) devolve as **regras** do seu assunto (`rules()`) e, quando produz efeito em outro serviço, a **sua compensação** (`compensations()`). É o mais próximo do "usecase por etapa" com `execute` e `compensar`, mas agrupa **várias regras de entrada**, não um único `execute`.
2. **`SagaDefinition`** junta todas as regras num índice por tipo de evento. Na subida, ela valida que todo evento tem pelo menos uma regra. Se você criar um evento e esquecer a regra, a aplicação não sobe.
3. **`SagaOrchestrator.handle(saga, evento)`** faz só isto:
   - pega a **primeira regra** cuja guarda passa e executa a ação (se a saga já terminou, só valem as regras marcadas com `afterTermination`);
   - registra a transição no histórico (evento, estado de origem, estado de destino, commands).

   Ele é o **motor** e não conhece nenhum step. Quem monta o fluxo de negócio é o `SagaFlow.standard()`, no pacote `flow`.
4. **`SagaInstance`** guarda os dados e o histórico. Só oferece operações (`moveTo`, `pause`...). Quem decide são as regras.

### A ordem das regras importa

Para o mesmo evento pode haver várias regras, e vale **a primeira cuja guarda passa**. A ordem é a dos steps em `SagaFlow.standard()` e, dentro do step, a ordem de declaração.

Exemplo em `CustomerDecisionStep`, o `BudgetCanceledEvent` tem duas regras:

1. `awaitingCompensation(CancelBudget)`: é a resposta ao cancelamento que a própria saga pediu, então só conclui a compensação.
2. `inState(BUDGET_GENERATED, AWAITING_CUSTOMER_DECISION)`: o orçamento expirou, então cancela a OS.

Se a ordem fosse invertida, o comportamento mudaria.

---

## 4. Compensação

Em vez de um `compensar` por usecase, cada step **declara as suas compensações** em `compensations()`. Uma `Compensation` tem três informações:

- a que etapa pertence;
- quando se aplica;
- qual command desfaz.

O `CompensationPlanner` (no motor) junta as compensações de todos os steps e as emite da etapa mais avançada para a mais antiga. Quem as declara:

| Step | Compensação |
|---|---|
| `RegistrationStep` | `CancelWorkOrder` (desfaz a abertura, a autorização e a execução da OS) |
| `BudgetStep` | `CancelBudget` |
| `PaymentStep` | `CancelPayment` (pagamento pedido e não pago) e `RefundPayment` (pagamento confirmado) |

Os demais steps só reagem a eventos e não têm o que desfazer. Quem decide **quando** compensar são as regras:

| Gatilho | Resultado |
|---|---|
| OS alterada com a saga em `BUDGET` ou `CUSTOMER_DECISION` | Cancela o orçamento e volta para `WORK_ORDER_OPEN` |
| OS cancelada por outra pessoa | Desfaz o que já foi feito (estorno, link, orçamento) e espera as confirmações |
| Recusa de command em qualquer etapa | Desfaz o que já foi feito, cancela a OS e espera as confirmações |

### Confirmação

O planner registra cada command de compensação como **pendente** na saga. Com a saga em `CANCELING`, ela só vai para `CANCELED` quando as pendências acabam. Cada step trata a confirmação das suas: o `PaymentStep` conclui o `CancelPayment` com o `PaymentCanceledEvent` e o `RefundPayment` com o `PaymentRefundedEvent`, o `CustomerDecisionStep` conclui o `CancelBudget` com o `BudgetCanceledEvent`, e o `CancellationStep` conclui o `CancelWorkOrder` com o `WorkOrderCanceledEvent`. A recusa do `CancelBudget` ou do `CancelPayment` também conclui (não havia o que cancelar). A recusa do `RefundPayment` **pausa** a saga.

Toda compensação gera um log `SAGA_COMPENSATED`. Não há "voltar etapa por etapa" automático: a compensação é só mais um conjunto de commands emitidos pela regra.

---

## 5. Falhas: pausa em vez de rollback

Quando algo falha, o saga **não tenta resolver sozinho**. Ele vai para `PAUSED`, guarda `pausedFrom` (o estado de onde veio) e espera uma pessoa agir.

| Pausa | Causa | Quem retoma |
|---|---|---|
| Envio do orçamento | `BudgetSentForDecisionFailedEvent` | `BudgetSentForDecisionEvent` |
| Link de pagamento | `PaymentLinkGenerationFailedEvent` | `PaymentLinkGeneratedEvent` |
| Pagamento | `PaymentFailedEvent` | `PaymentConfirmedEvent` |
| Cancelamento da OS recusado | `WorkOrderCommandRejectedEvent` | Nenhum evento retoma sozinho, é preciso intervenção manual |
| Estorno recusado | `FinanceCommandRejectedEvent` do `RefundPayment` | O `PaymentRefundedEvent`, quando o Finance corrigir o estorno |
| Evento inesperado | Evento à frente do estado da saga | Nenhum evento retoma sozinho, é preciso intervenção manual |

Toda pausa gera um log `SAGA_PAUSED`, e é por ele que a equipe fica sabendo, já que os outros serviços não têm tela nem consomem eventos da saga.

A retomada usa a guarda `pausedFrom(...)`: só o evento certo, vindo da pausa certa, reativa a saga. Um evento que não resolve aquela pausa é ignorado.

### Recusa de command (`CommandRejectionStep`)

Esta é a peça mais parecida com a "configuração no enum" do modelo por etapas. Uma recusa significa que o efeito do command **não aconteceu**, então o que se desfaz são os passos anteriores. A saga só reage **se aquele era o command que o estado atual esperava**, e a reação depende de quanto ela avançou:

| Estado | Command esperado | Reação |
|---|---|---|
| `AWAITING_BUDGET` | `GenerateBudgetIntegrationCommand` | Compensa e cancela a OS |
| `AWAITING_EXECUTION` | `AuthorizeWorkOrderIntegrationCommand` | Compensa e cancela a OS |
| `CHANGES_REQUESTED` | `ChangesRequestedWorkOrderIntegrationCommand` | Compensa e cancela a OS |
| `AWAITING_PAYMENT_LINK` | `GeneratePaymentIntegrationCommand` | Compensa e cancela a OS |
| `AWAITING_DELIVERY` | `ReleaseWorkOrderVehicleIntegrationCommand` | Compensa e cancela a OS |
| `CANCELING` | `CancelWorkOrderIntegrationCommand` | Pausa |

A recusa de um command em qualquer etapa desfaz o que já foi feito. Só a recusa do próprio cancelamento pausa, porque não há como compensar a compensação. Qualquer outra recusa só entra no histórico.

### Evento inesperado (`UnexpectedEventStep`)

Um evento **à frente** do ponto em que a saga está (por exemplo, pagamento confirmado antes da decisão do cliente) pausa a saga com o motivo `UnexpectedEvent`. Eventos atrasados ou duplicados continuam ignorados. Falha técnica (banco fora, bug) **não** compensa: a transação é desfeita e a mensagem vai para a DLQ. Exemplo: o Finance recusar o `CancelBudgetIntegrationCommand` de uma compensação porque não havia orçamento.

---

## 6. Trazendo para o seu modelo mental

- O `SagaState` é como o enum de etapas, mas **sem sequência embutida**.
- As guardas são o "status da entidade exigido".
- A sequência é **emergente**: o `BudgetStep` pede o orçamento e muda para `AWAITING_BUDGET`. A próxima coisa só acontece quando o Finance publicar `BudgetGeneratedEvent`.
- Você nunca chama a próxima etapa. Você emite um command e espera o evento de volta.

**Vantagem:** os serviços ficam desacoplados e o fluxo tolera caminhos laterais (cancelamento, edição, expiração) sem `if` em cascata.

**Custo:** para entender o fluxo você precisa ler várias regras espalhadas, em vez de uma sequência linear. Para isso, use a tabela do caminho feliz em [como-o-saga-funciona.md](como-o-saga-funciona.md) (seção 5) e a tabela completa de transições em [saga-fluxo.md](saga-fluxo.md).

---

## 7. Onde está cada coisa

```
core/saga/engine/                 o motor, genérico (não conhece nenhum step)
  TransitionRule.java             a regra (evento + guarda + ação)
  Guards.java                     as guardas prontas
  SagaFlowStep.java               contrato de um step: rules() e compensations()
  Compensation.java               como desfazer um efeito (etapa + quando + command)
  CompensationPlanner.java        junta as compensações dos steps, registra as pendentes
  SagaDefinition.java             junta as regras e valida a cobertura de eventos
  SagaOrchestrator.java           escolhe a regra e registra o histórico
core/saga/flow/                   o fluxo de negócio
  SagaFlow.java                   monta os steps, o planner e o orquestrador
  RegistrationStep.java ...       um step por assunto, cada um com suas regras e sua compensação
core/saga/domain/
  SagaInstance.java               dados, histórico e compensações pendentes da saga
  SagaState.java / SagaStep.java
```

Para mudar o negócio, você mexe em `flow/`. O `engine/` só muda se mudar a forma de o saga funcionar.
