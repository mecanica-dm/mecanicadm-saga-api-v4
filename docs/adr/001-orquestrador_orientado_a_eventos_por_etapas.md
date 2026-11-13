# [001]: [Orquestrador orientado a eventos, com o fluxo declarado por etapas]

* **Status**: Aceito
* **Data**: 12/11/2026

## 1. Contexto e Problema

A [RFC 001](../rfc/001-escolha_do_padrao_saga_orquestrado.md) definiu uma saga orquestrada. Falta decidir **como o
orquestrador é construído**. O fluxo de uma OS não é uma sequência fixa: o cliente pode aprovar, rejeitar ou pedir
alterações, a OS pode ser alterada ou cancelada em qualquer fase, e os outros serviços respondem de forma assíncrona e
fora de ordem.

Um orquestrador que percorre uma lista de passos em ordem não representa isso bem, e um `if` por evento dentro de uma
classe única viraria um bloco difícil de ler e de testar.

## 2. Decisão

O orquestrador é uma **máquina de estados orientada a eventos**, com o fluxo **declarado por etapas** sobre um motor
genérico:

| Peça | Papel |
|---|---|
| `SagaState` e `SagaStep` | O estado exato da saga e a etapa do negócio a que ele pertence (registro, orçamento, decisão, execução, pagamento, entrega) |
| `TransitionRule` | Um evento, uma **guarda** (condição sobre o estado) e uma **ação** (muda o estado e devolve os commands) |
| `SagaFlowStep` (uma classe por etapa) | Declara as regras da sua etapa e as suas compensações |
| `SagaDefinition` | Junta as regras de todas as etapas e **falha na montagem** se algum evento da saga ficar sem regra |
| `SagaOrchestrator` | Recebe a saga e o evento, aplica a primeira regra cuja guarda passa e registra a transição no histórico |

- O motor (`core/saga/engine`) é genérico e **não tem regra de negócio**. O negócio fica em `core/saga/flow`.
- Dentro de um evento, as regras são avaliadas **na ordem em que foram declaradas** e vale a primeira que passa.
- O evento que não se aplica ao estado atual **não muda nada**: é registrado no histórico com o mesmo estado de origem e
  de destino. Em `COMPLETED` e `CANCELED` todo evento é ignorado.
- `SagaEvent` é uma interface `sealed`: um evento novo só compila com a sua regra declarada, e a montagem do fluxo
  verifica isso.

## 3. Consequências

### Positivas

- Cada etapa é pequena, tem uma responsabilidade e se testa sozinha.
- O fluxo é legível: as regras de uma etapa mostram "se chegar X no estado Y, faça Z".
- Incluir um passo é criar o evento, declarar a regra na etapa certa e, se houver efeito a desfazer, uma compensação.
- Esquecer de tratar um evento é um erro de montagem, e não um defeito descoberto em produção.

### Negativas / Custos

- Não há uma "sequência" única para ler: quem vem de um orquestrador por lista de passos precisa ler as regras de cada etapa.
- A ordem das regras de um mesmo evento importa e precisa ser mantida com cuidado.

## 4. Relação com Outras Decisões

- [RFC 001 - Escolha do Padrão Saga](../rfc/001-escolha_do_padrao_saga_orquestrado.md)
- [ADR 002 - Compensação com confirmação e pausa](002-compensacao_com_confirmacao_e_pausa.md)
