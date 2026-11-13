# [008]: [Política para eventos inesperados, atrasados e duplicados]

* **Status**: Aceito
* **Data**: 12/11/2026

## 1. Contexto e Problema

Os eventos chegam por mensageria, que não garante a ordem entre serviços diferentes e entrega **pelo menos uma vez**. Logo, a
saga recebe com frequência eventos que "não fazem sentido" para o estado em que ela está:

- um evento **repetido** ou **atrasado** (de uma etapa que a saga já passou);
- um evento **à frente** do que ela espera (por exemplo, `PaymentConfirmedEvent` com a saga ainda esperando a decisão do
  cliente), o que normalmente indica uma anomalia;
- eventos de orçamento que chegam fora de ordem **de forma legítima**, quando a OS é alterada logo depois do pedido do
  orçamento.

Tratar todos como erro derrubaria o fluxo por ruído normal. Ignorar todos esconderia anomalias reais.

## 2. Decisão

### 2.1 Três categorias, três reações

| Categoria | Reação |
|---|---|
| **Atrasado ou duplicado** (evento de uma etapa já passada ou da etapa atual, sem regra que case) | **Ignora**: nada muda, mas a transição é registrada no histórico, com o mesmo estado de origem e de destino |
| **À frente** do ponto em que a saga está (etapa do evento posterior à etapa atual) | **Pausa** com o motivo `UnexpectedEvent: <evento> recebido no estado <estado> (etapa <etapa>)` |
| **Saga encerrada** (`COMPLETED` ou `CANCELED`) | **Ignora** todo evento |

### 2.2 O que nunca pausa por "estar à frente"

- eventos da etapa de orçamento e o `BudgetCanceledEvent`, que chegam fora de ordem de forma legítima quando a OS é alterada;
- os eventos de decisão do orçamento quando a **própria saga pediu o cancelamento** do orçamento (a resposta ao seu pedido);
- qualquer evento com a saga já **pausada** ou em `CANCELING`.

### 2.3 Onde a regra mora

No `UnexpectedEventStep`, com uma regra por evento mapeado à sua etapa (`BudgetApprovedEvent` e similares à decisão do cliente,
os eventos de execução, de pagamento e de entrega). Ela é a **última** da montagem: só vale quando nenhuma regra de negócio
casou.

### 2.4 Falha técnica não passa por aqui

Banco fora, bug ou conflito de lock otimista não são eventos inesperados: a transação é desfeita e a mensagem vai para a DLQ
([ADR 003](003-outbox_inbox_e_politica_de_falhas.md)). O evento já aconteceu no outro serviço, então o caminho é reprocessar, e
não desfazer.

## 3. Consequências

### Positivas

- Ruído normal da mensageria (repetição, atraso) não pausa nem polui o fluxo, e fica rastreável no histórico.
- Uma anomalia real aparece como pausa com motivo, em vez de passar em silêncio.
- A saga não se desfaz por causa de um evento fora de lugar: pausa e espera uma pessoa.

### Negativas / Custos

- A pausa por evento inesperado **não tem evento que a retome**: o caminho é cancelar a OS ou corrigir o documento da saga
  (veja o README).
- O mapa "evento para etapa" precisa ser mantido junto com os eventos: um evento novo de uma etapa posterior sem entrada no mapa
  não será reconhecido como "à frente".

## 4. Relação com Outras Decisões

- [ADR 001 - Orquestrador orientado a eventos](001-orquestrador_orientado_a_eventos_por_etapas.md)
- [ADR 002 - Compensação com confirmação e pausa](002-compensacao_com_confirmacao_e_pausa.md)
- [ADR 005 - Eventos de recusa de command](005-eventos_de_recusa_de_command.md)
