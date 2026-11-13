# [004]: [Contratos das mensagens definidos pelo consumidor]

* **Status**: Aceito
* **Data**: 12/11/2026

## 1. Contexto e Problema

A saga e os outros dois serviços só se falam por mensagens, então o **formato dessas mensagens** é o contrato entre eles.
Sem uma regra clara sobre quem o define, cada lado "adivinha" o do outro e o erro só aparece em execução.

Um caso real: o Finance já consumia o `GenerateBudgetIntegrationCommand` com os campos `workOrderId` e
`workOrderSnapshot` (`totalPrice`, `status` do orçamento, `client`, `vehicle`, `materialItems` com `price` unitário e
`laborItems`). A saga, porém, enviava `workOrder`, com a estrutura do evento da Workorder. O Finance ignoraria o campo
desconhecido e receberia o command sem o `workOrderSnapshot`.

## 2. Decisão

1. **Quem consome define o contrato.** O formato de um command é o que o serviço que o executa já lê; o produtor (a
   saga) se adapta.
2. **Um tipo próprio por contrato.** A saga tem um record para o payload de cada command que não é trivial
   (por exemplo, `BudgetWorkOrderSnapshot`), com uma fábrica a partir do retrato interno da saga. O formato interno da
   saga não vaza para a mensagem.
3. **Testes de contrato nos dois lados.**
   - Na saga, `GenerateBudgetCommandSerializationTest` confere os campos exatos do JSON publicado.
   - No Finance, `GenerateBudgetContractTest` lê um payload no formato que a saga publica.
4. **Campos desconhecidos são ignorados** (`FAIL_ON_UNKNOWN_PROPERTIES` desligado), o que permite evoluir um lado sem
   quebrar o outro, desde que o contrato combinado seja mantido.
5. **Convenções comuns:** o nome da classe vai no header `event-type`, o identificador único em `message-id` e o
   `workOrderId` em todas as mensagens.

### Exemplo: `GenerateBudgetIntegrationCommand`

| Campo | Valor enviado pela saga |
|---|---|
| `totalPrice` | Soma da mão de obra e dos materiais do retrato (calculada pela saga) |
| `status` | `PENDING`, o status inicial do orçamento no Finance |
| `materialItems[].price` | Preço **unitário** (o Finance multiplica pela quantidade) |
| `laborItems[].status` | O mesmo nome da Workorder (`AWAITING_EXECUTION`, `IN_EXECUTION`, `EXECUTION_COMPLETED`) |

## 3. Consequências

### Positivas

- O contrato tem dono e fica registrado em código e em teste.
- Uma divergência é pega pelo teste de um dos lados, e não em produção.
- O formato interno da saga pode mudar sem afetar os outros serviços.

### Negativas / Custos

- A saga precisa converter o retrato interno para cada contrato.
- Os testes de contrato dos dois lados precisam ser mantidos em sincronia (não há um repositório de contratos
  compartilhado).

## 4. Relação com Outras Decisões

- [RFC 001 - Escolha do Padrão Saga](../rfc/001-escolha_do_padrao_saga_orquestrado.md)
- [ADR 005 - Eventos de recusa de command](005-eventos_de_recusa_de_command.md)
