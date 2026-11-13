# [003]: [Outbox, inbox e política de falhas no consumo de mensagens]

* **Status**: Aceito
* **Data**: 12/11/2026

## 1. Contexto e Problema

O RabbitMQ entrega cada mensagem **pelo menos uma vez**. Isso cria dois problemas para a saga:

- **Salvar e publicar não é uma operação só.** Se a saga gravar o novo estado e cair antes de publicar o command, o fluxo
  trava; se publicar antes de gravar, o outro serviço age sobre algo que a saga não registrou.
- **A mesma mensagem pode chegar duas vezes.** Reprocessar um evento repetiria transições e commands.

Também é preciso decidir o que fazer quando o processamento falha: descartar, repetir ou isolar a mensagem.

## 2. Decisão

### 2.1 Outbox transacional

Ao tratar um evento, a saga grava, **na mesma transação do MongoDB**, o novo estado da saga e os commands na coleção
`outbox`. Um publicador agenda o envio à exchange `saga-output` e só marca como publicado depois do *publisher confirm*
do broker.

- Os commands são ordenados por `sequence` (relógio em microssegundos, monotônico por instância), o que preserva a ordem
  de envio.
- O publicador só trabalha com o **lock** `outbox-publisher` (lease calculado pelo tamanho do lote e pelo timeout de
  confirmação). Com várias instâncias a ordem se mantém, e o lock de uma instância que cai expira sozinho.

### 2.2 Inbox idempotente

Cada mensagem recebida é registrada na coleção `inbox` pela chave `message-id` (header), com `INSERT` condicional na
mesma transação do processamento.

| Status do inbox | Significado | Reprocessável com o mesmo `message-id`? |
|---|---|---|
| `PROCESSED` | Já tratada | Não: é duplicata e recebe ack sem efeito |
| `REJECTED` | Recusada por regra de negócio (por exemplo, evento de OS sem saga) | Sim |
| `FAILED` | Falha técnica | Sim |

### 2.3 Política de falhas

| Situação | Reação |
|---|---|
| Duplicata | Ack sem executar |
| Recusa de negócio (`DomainExceptionCore`) | Ack, com `REJECTED` no inbox |
| Falha técnica (banco fora, bug, lock otimista) | A transação é desfeita, o inbox registra `FAILED` e a mensagem vai para a **DLQ** (`nack` sem reenfileirar) |
| Mensagem sem `message-id` ou `workOrderId`, ou com `event-type` desconhecido | DLQ |

A saga **não compensa** em falha técnica: nada foi gravado, e a mensagem pode ser reprocessada depois.

### 2.4 Concorrência

Um único consumer escuta as duas filas de entrada, então uma instância trata um evento por vez. Se duas instâncias
tratarem eventos da mesma OS ao mesmo tempo, a segunda falha por **lock otimista** (`@Version`), a mensagem vai para a
DLQ e pode ser reprocessada.

## 3. Consequências

### Positivas

- Nenhum command se perde, e nenhum é enviado sem o estado correspondente gravado.
- Reentregas e mensagens duplicadas não repetem efeitos.
- O destino de cada mensagem é previsível e fica registrado no inbox.

### Negativas / Custos

- Latência extra: o command sai pelo publicador, e não na hora (por padrão, o intervalo é de 1 segundo).
- Mais coleções (`inbox`, `outbox`, `locks`) e limpeza periódica.
- A entrega continua *at-least-once*: o efeito é que passa a ser *effectively-once*.

## 4. Relação com Outras Decisões

- [RFC 002 - Escolha do MongoDB](../rfc/002-escolha_do_mongodb_como_banco_da_saga.md): a transação saga mais outbox exige
  replica set.
- ADR 003 da Finance API (estratégia de inbox) e ADR 013 da Workorder API (outbox, inbox e política de ack): o mesmo padrão
  nos outros serviços.
