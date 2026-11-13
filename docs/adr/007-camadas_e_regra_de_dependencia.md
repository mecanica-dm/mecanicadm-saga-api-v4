# [007]: [Camadas e regra de dependência]

* **Status**: Aceito
* **Data**: 12/11/2026

## 1. Contexto e Problema

A saga concentra regra de negócio sensível (o fluxo da OS e as compensações) e, ao mesmo tempo, depende de tecnologias que
podem mudar ou falhar: MongoDB, RabbitMQ, Spring. Se a regra do fluxo conhecer essas tecnologias, ela passa a ser testável só
com banco e broker no ar, e qualquer troca de infraestrutura atinge o coração do sistema.

Há ainda uma segunda mistura a evitar: o **motor** que aplica regras de transição é genérico, e o **fluxo de negócio** é
específico da oficina. Sem separá-los, cada regra nova mexeria no motor.

## 2. Decisão

### 2.1 Duas camadas, com dependência em um só sentido

| Camada | Conteúdo | Pode depender de |
|---|---|---|
| `core` (e `shared`) | Regra de negócio em Java puro: eventos, commands, o agregado, o motor, o fluxo, os casos de uso e as portas | Só Java e o próprio projeto |
| `infra` | Spring, MongoDB e RabbitMQ: consumers, persistência, outbox, inbox, API HTTP, segurança e configuração | `core` |

**A regra:** `infra` depende de `core`, nunca o contrário. O `core` declara **portas** (interfaces) e a `infra` as implementa:

| Porta (em `core`) | Implementação (em `infra`) |
|---|---|
| `SagaGateway` | `SagaRepositoryImpl` (MongoDB) |
| `SagaQueryGateway` | `SagaRepositoryImpl` (consulta com filtros) |
| `MessagePublisher` | `OutboxMessagePublisher` (grava na outbox) |

### 2.2 Motor separado do fluxo

Dentro do `core/saga`:

- **`engine`**: o motor genérico (`SagaOrchestrator`, `SagaDefinition`, `TransitionRule`, `Guards`, `CompensationPlanner`). Não
  conhece nenhum evento do negócio.
- **`flow`**: o fluxo de negócio, uma classe por etapa (`BudgetStep`, `PaymentStep`...), mais `SagaFlow`, que as monta.
- **`domain`, `event`, `command`**: o agregado `SagaInstance` e as mensagens.
- **`usecase`**: os casos de uso (processar um evento, consultar sagas), que só orquestram portas e o motor.

Incluir um passo novo no fluxo é mexer em `event`, `command` e `flow`, e nunca no `engine`.

### 2.3 Os beans são montados na borda

Os casos de uso são classes comuns (sem anotações do Spring) e são registrados como beans em `infra/features/saga/config`. A
transação do caso de uso que grava a saga é aplicada por um proxy (`TransactionalProxy`) também na `infra`.

## 3. Consequências

### Positivas

- O fluxo e as compensações são testados por testes unitários puros, sem banco, sem broker e sem contexto do Spring (a maior
  parte dos 183 testes unitários).
- Trocar o banco, o broker ou o framework não toca o `core`.
- O motor é reutilizável e fica estável enquanto o fluxo evolui.

### Negativas / Custos

- Mais arquivos e mais mapeamento entre as camadas (por exemplo, `SagaInstance` e `SagaDocument`).
- A regra "o `core` não importa a `infra`" depende de disciplina e de revisão; não há, hoje, um teste de arquitetura que a
  imponha automaticamente.

## 4. Relação com Outras Decisões

- [ADR 001 - Orquestrador orientado a eventos](001-orquestrador_orientado_a_eventos_por_etapas.md)
- ADR 011 da Workorder API (arquitetura limpa): o mesmo estilo nos outros serviços.
