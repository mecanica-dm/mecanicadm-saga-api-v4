# [002]: [Compensação com confirmação e pausa para o que não se compensa]

* **Status**: Aceito
* **Data**: 12/11/2026

## 1. Contexto e Problema

Quando o fluxo é cancelado (o cliente rejeita, o orçamento expira, um serviço recusa um command, a OS é cancelada),
é preciso desfazer o que já foi feito, e o que já foi feito depende da fase: nada, um orçamento, um link de pagamento ou
um pagamento confirmado.

Três situações tornam isso mais difícil do que "enviar os cancelamentos":

- enviar um cancelamento **não garante** que ele foi feito (o outro serviço pode recusar ou falhar);
- o cliente pode **pagar depois** do cancelamento, e o dinheiro entra para uma OS cancelada;
- algumas compensações **não têm como ser desfeitas** (um estorno recusado, ou a OS que não aceita ser cancelada).

## 2. Decisão

### 2.1 Cada etapa declara a sua compensação

Cada `SagaFlowStep` informa, em `compensations()`, qual command desfaz o efeito da etapa e **quando ele se aplica**. O
`CompensationPlanner` as emite **da etapa mais avançada para a mais antiga**.

| Etapa concluída | Compensação |
|---|---|
| Abertura, autorização e execução da OS | `CancelWorkOrderIntegrationCommand` |
| Orçamento | `CancelBudgetIntegrationCommand` |
| Pagamento pedido, não pago | `CancelPaymentIntegrationCommand` |
| Pagamento confirmado | `RefundPaymentIntegrationCommand` |

### 2.2 A saga espera a confirmação de cada compensação

Os commands enviados ficam em `pendingCompensations`, no documento da saga. Ela só vai de `CANCELING` para `CANCELED`
quando todos são confirmados pelo evento correspondente (`WorkOrderCanceledEvent`, `BudgetCanceledEvent`,
`PaymentCanceledEvent`, `PaymentRefundedEvent`).

### 2.3 O que a recusa de uma compensação significa

| Compensação recusada | Reação | Motivo |
|---|---|---|
| `CancelBudget` ou `CancelPayment` | A saga dá a compensação por concluída | Em geral só quer dizer que não havia o que cancelar |
| `RefundPayment` | **Pausa** e registra `SAGA_COMPENSATION_FAILED` | Não há como compensar o estorno; exige ação manual |
| `CancelWorkOrder` | **Pausa** | Não há como compensar o cancelamento da OS |

### 2.4 Pagamento depois do cancelamento

Se `PaymentConfirmedEvent` chegar com a saga em `CANCELING` ou `CANCELED`, a saga emite `RefundPayment`. Se um
`PaymentLinkGeneratedEvent` chegar depois do cancelamento, emite `CancelPayment`. Os dois casos entram nas compensações
pendentes enquanto a saga ainda está cancelando.

### 2.5 Pausa e retomada

Falhas que a saga não resolve sozinha a levam a `PAUSED`, guardando o estado de origem (`pausedFrom`) e o motivo. Só o
evento que resolve aquela falha a retoma. A visibilidade vem dos logs `SAGA_PAUSED`, `SAGA_COMPENSATED` e
`SAGA_COMPENSATION_FAILED` e da API de consulta.

## 3. Consequências

### Positivas

- O cancelamento só termina quando tudo foi de fato desfeito, e o estado mostra o que falta.
- Cada compensação mora ao lado da etapa que a exige, e não num bloco central de `if`.
- O dinheiro que chega depois do cancelamento não fica esquecido: é estornado.

### Negativas / Custos

- A saga depende de o outro serviço **publicar a confirmação**. Se ele não publicar, a saga fica em `CANCELING`.
- Uma pausa por estorno recusado precisa de pessoa para resolver (cancelar a OS ou corrigir o documento da saga).

## 4. Relação com Outras Decisões

- [ADR 001 - Orquestrador orientado a eventos](001-orquestrador_orientado_a_eventos_por_etapas.md)
- [ADR 005 - Eventos de recusa de command](005-eventos_de_recusa_de_command.md)
