# [010]: [Topologia do RabbitMQ]

* **Status**: Aceito
* **Data**: 12/11/2026

## 1. Contexto e Problema

Três serviços trocam mensagens por um mesmo broker. É preciso definir **quem publica onde**, **como cada mensagem chega ao
destino certo** e **para onde vai a que falha**, de modo que os serviços continuem independentes: a Workorder e o Finance não
devem conhecer filas um do outro, e adicionar um tipo de mensagem não deve exigir mudar o código de roteamento.

## 2. Decisão

### 2.1 Exchanges `headers`, roteadas pelo `event-type`

Todas as exchanges de negócio são do tipo `headers`, e o roteamento usa o header `event-type` (o nome da classe da mensagem),
com `x-match = all`. O código nunca usa routing key.

### 2.2 Um par de exchanges por serviço

| Exchange | Quem publica | Para onde roteia |
|---|---|---|
| `workorder-output` | Workorder (eventos) | `saga.workorder-events` (8 eventos) |
| `finance-output` | Finance (eventos) | `saga.finance-events` (16 eventos) |
| `saga-output` | Saga (commands) | `workorder-input` e `finance-input` (exchange para exchange, sem filtro) |
| `workorder-input` | `saga-output` | fila `workorder` (4 commands) |
| `finance-input` | `saga-output` | fila `budget` (2 commands) e fila `payment` (3 commands) |

A saga publica **tudo** na `saga-output`, que repassa a cada serviço, e cada `*-input` filtra só o que o serviço consome. Assim a
saga não conhece nenhuma fila de destino.

### 2.3 Uma DLQ por fila

Cada fila tem a sua DLQ (`<fila>.dlq`), alimentada pelo dead-letter exchange configurado na própria fila:

| Filas | Dead-letter exchange | DLQs |
|---|---|---|
| `workorder`, `saga.workorder-events`, `saga.finance-events` | `mecanicadm.dlx` (`topic`) | `workorder.dlq`, `saga.workorder-events.dlq`, `saga.finance-events.dlq` |
| `budget`, `payment` | `finance-dlx` (`direct`, chave = nome da fila) | `budget.dlq`, `payment.dlq` |

### 2.4 Uma definição única para os três serviços

O arquivo `messaging/rabbitmq-definitions.json` descreve **toda** a topologia (exchanges, filas, DLQs e bindings) e é importado de
uma vez no broker. No ambiente local, o compose da saga (perfil `broker`) o aplica por um contêiner de inicialização. Cada
serviço mantém também o seu arquivo parcial (`workorder-definitions.json`, `definitions.json` do Finance), que serve ao
pipeline e ao uso isolado.

### 2.5 Um teste garante que nada fica sem rota

O `RabbitMQConfigTest` confere que **todo evento da saga tem um binding** na definição e que todo evento é convertido pelo
nome da classe.

## 3. Consequências

### Positivas

- Os serviços só conhecem as suas próprias exchanges; a saga não conhece filas.
- Um tipo novo de mensagem é só um binding novo, sem código de roteamento.
- Toda falha tem um destino inspecionável (a DLQ da fila).

### Negativas / Custos

- **Uma mensagem sem binding é descartada pelo broker** (uma exchange `headers` sem correspondência não roteia). Um tipo novo
  de mensagem **precisa** entrar na definição, ou se perde em silêncio; o teste acima cobre os eventos da saga, e os commands
  são cobertos pelos testes de integração de mensageria.
- O nome da classe da mensagem é parte do contrato de roteamento: renomear uma classe muda o `event-type`.
- A definição única mora no repositório da saga, e os outros serviços dependem de que ela esteja aplicada no broker.

## 4. Relação com Outras Decisões

- [ADR 003 - Outbox, inbox e política de falhas](003-outbox_inbox_e_politica_de_falhas.md)
- [ADR 004 - Contratos definidos pelo consumidor](004-contratos_definidos_pelo_consumidor.md)
- ADR 002 da Finance API (exchange do tipo headers).
