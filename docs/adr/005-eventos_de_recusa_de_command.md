# [005]: [Eventos de recusa de command]

* **Status**: Aceito
* **Data**: 12/11/2026

## 1. Contexto e Problema

A saga envia commands e espera um evento de resposta. Quando o serviço de destino **recusa** o command por regra de
negócio (por exemplo, autorizar uma OS que já está em execução), ele grava a recusa no seu inbox e dá ack, mas **não avisa
ninguém**. A saga fica esperando uma resposta que não vem, e o fluxo trava sem que ninguém saiba por quê.

Falha técnica é diferente: a mensagem vai para a DLQ e é reprocessada. Só a recusa de negócio precisa ser comunicada.

## 2. Decisão

Cada serviço publica um **evento de recusa** quando recusa um command por regra de negócio:

| Serviço | Evento |
|---|---|
| Workorder API | `WorkOrderCommandRejectedEvent` |
| Finance API | `FinanceCommandRejectedEvent` |

Ambos têm os mesmos campos:

| Campo | Conteúdo |
|---|---|
| `workOrderId` | A OS do command recusado |
| `eventType` | O nome do command recusado (por exemplo, `AuthorizeWorkOrderIntegrationCommand`) |
| `exception` | O nome da exceção de domínio (por exemplo, `InvalidStatusTransition`) |
| `message` | O motivo, traduzido |

### 2.1 Como a saga reage (`CommandRejectionStep`)

A saga só reage se o command recusado for **o que o estado atual está esperando**:

| Estado | Command esperado | Reação à recusa |
|---|---|---|
| `AWAITING_BUDGET` | `GenerateBudgetIntegrationCommand` | Compensa e cancela a OS |
| `AWAITING_EXECUTION` | `AuthorizeWorkOrderIntegrationCommand` | Compensa e cancela a OS |
| `CHANGES_REQUESTED` | `ChangesRequestedWorkOrderIntegrationCommand` | Compensa e cancela a OS |
| `AWAITING_PAYMENT_LINK` | `GeneratePaymentIntegrationCommand` | Compensa e cancela a OS |
| `AWAITING_DELIVERY` | `ReleaseWorkOrderVehicleIntegrationCommand` | Compensa e cancela a OS |
| `CANCELING` | `CancelWorkOrderIntegrationCommand` | **Pausa** |

Qualquer outra recusa (por exemplo, o Finance recusar um `CancelBudget` de compensação porque não havia orçamento) não
muda o fluxo e fica só no histórico. A recusa de `CancelBudget` ou `CancelPayment` durante o cancelamento dá a
compensação por concluída, e a de `RefundPayment` pausa a saga (ADR 002).

## 3. Consequências

### Positivas

- A saga sabe que o command foi recusado e por quê, e decide em vez de travar.
- O motivo fica no histórico da saga (`pauseReason`) e no log.
- O filtro "só o command esperado" evita que uma recusa irrelevante derrube um fluxo saudável.

### Negativas / Custos

- Dois eventos a mais por serviço e mais um passo (`CommandRejectionStep`) na saga.
- O `eventType` precisa conter o **nome exato** do command; um nome diferente nunca casa e a recusa é ignorada.
- Falha técnica continua sem evento (vai para a DLQ), então uma falha técnica persistente ainda exige olhar a fila.

## 4. Relação com Outras Decisões

- [ADR 002 - Compensação com confirmação e pausa](002-compensacao_com_confirmacao_e_pausa.md)
- [ADR 003 - Outbox, inbox e política de falhas](003-outbox_inbox_e_politica_de_falhas.md)
- [ADR 004 - Contratos definidos pelo consumidor](004-contratos_definidos_pelo_consumidor.md)
- ADR 012 da Workorder API e ADR 004 da Finance API: a publicação do evento de recusa em cada serviço.
