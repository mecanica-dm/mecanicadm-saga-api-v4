# [009]: [Retrato da OS guardado na saga e total calculado pela saga]

* **Status**: Aceito
* **Data**: 12/11/2026

## 1. Contexto e Problema

Para pedir o orçamento, o Finance precisa dos dados da OS (cliente, veículo, mão de obra e materiais) e do total. A Workorder e
o Finance **não se conhecem** e não se chamam por HTTP, e o Finance não escuta eventos da Workorder. Alguém precisa levar esses
dados da OS ao Finance, no momento certo, e responder por qual é o valor do orçamento.

Duas perguntas ficam em aberto:

- **De onde a saga tira os dados** no momento do diagnóstico, se o evento do diagnóstico traz só o `workOrderId`?
- **Quem soma o total**: a Workorder (que conhece os preços), o Finance (que recebe os itens) ou a saga?

## 2. Decisão

### 2.1 A saga guarda o retrato da OS

A saga guarda no seu documento (`saga_instances.workOrder`) o **retrato mais recente** da OS, atualizado a cada
`WorkOrderCreatedEvent` e `WorkOrderUpdatedEvent`. Esses eventos levam os dados da OS no corpo (cliente, veículo, tarefas e
materiais), justamente para a saga não precisar consultar a Workorder.

Se a OS é alterada com um orçamento já ativo (nas etapas de orçamento ou de decisão do cliente), a saga atualiza o retrato,
compensa o orçamento e volta a `WORK_ORDER_OPEN`; o orçamento novo nasce no diagnóstico seguinte, **com o retrato atualizado**.

### 2.2 O total é calculado pela saga

O total é a **soma dos preços da mão de obra e do `totalPrice` dos materiais** do retrato guardado (`WorkOrderSnapshot.totalPrice()`).
A Workorder API não soma nada, e o Finance recebe o total pronto no `GenerateBudgetIntegrationCommand`.

### 2.3 O retrato viaja no formato do consumidor

No command, o retrato é convertido para o contrato que o Finance lê (`BudgetWorkOrderSnapshot`, com o preço unitário nos
materiais e o status inicial do orçamento), conforme o [ADR 004](004-contratos_definidos_pelo_consumidor.md).

## 3. Consequências

### Positivas

- Nenhum serviço consulta outro: o dado da OS chega no evento, e o do orçamento, no command.
- Existe um único lugar que sabe o total do orçamento, e ele é calculado sobre o retrato que a saga já conhece.
- Um orçamento refeito depois de alterações usa a OS alterada, sem coordenação extra.

### Negativas / Custos

- **Duplicação de dados.** A saga guarda uma cópia da OS (e, nela, dados pessoais do cliente: nome, e-mail, documento e telefone).
  A API de consulta expõe só o nome do cliente e a placa, mas o dado está no banco da saga.
- **Retrato pode ficar defasado** se uma alteração da OS não gerar o `WorkOrderUpdatedEvent`; a garantia vem da Workorder
  publicá-lo em toda mudança de itens.
- O formato do evento da Workorder passa a ser parte do contrato da saga: mudar o corpo dos eventos `Created` e `Updated` exige
  mudar a saga.

## 4. Relação com Outras Decisões

- [ADR 001 - Orquestrador orientado a eventos](001-orquestrador_orientado_a_eventos_por_etapas.md)
- [ADR 004 - Contratos definidos pelo consumidor](004-contratos_definidos_pelo_consumidor.md)
