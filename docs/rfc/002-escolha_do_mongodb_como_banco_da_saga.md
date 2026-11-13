# RFC 002 - Escolha do Banco de Dados da Saga

* **Status**: Aprovado
* **Data**: 12/11/2026
* **Autores**: Time de desenvolvimento
* **Resumo**: Este documento propõe o **MongoDB**, em **replica set**, como banco de dados da Saga API, atendendo ao
  requisito do desafio de usar ao menos um banco não relacional.

---

## 1. Resumo

Cada microsserviço precisa de banco próprio, e o desafio exige ao menos um banco relacional (SQL) e ao menos um não
relacional (NoSQL) no conjunto. A Workorder e o Finance usam PostgreSQL. Recomendamos o **MongoDB** para a saga.

## 2. Contexto

O que a saga guarda tem formato de **documento**, não de tabelas ligadas:

- o estado da saga (`state`, `pausedFrom`, `pauseReason`, compensações pendentes);
- o **retrato da OS** (cliente, veículo, mão de obra e materiais), que muda de forma conforme a OS;
- o **histórico** de transições, que só cresce (um registro por evento) e é sempre lido junto com a saga.

Além disso, ao tratar um evento a saga precisa gravar o novo estado **e** os commands na outbox de forma atômica.

## 3. Objetivos

- Atender ao requisito de ter um banco NoSQL no sistema.
- Guardar a saga, o retrato da OS e o histórico em **um documento**, lido e gravado de uma vez.
- Manter a atomicidade entre a saga e a outbox.
- Ter controle de concorrência por saga.

## 4. Alternativas Consideradas

### 4.1. MongoDB (replica set)

**Prós:**

- O documento da saga (estado, retrato da OS e histórico embutido) é lido e gravado inteiro, sem junções;
- O retrato da OS tem estrutura aninhada que mapeia direto para um subdocumento;
- Transações multidocumento (saga e outbox na mesma transação) e `@Version` para lock otimista;
- Atende ao requisito NoSQL.

**Contras:**

- As transações exigem **replica set**, mesmo com um nó só, o que pesa no ambiente local e na nuvem;
- O histórico embutido cresce junto com a saga (aceitável: uma saga tem dezenas de transições, não milhares).

### 4.2. PostgreSQL (como nos outros serviços)

**Prós:**

- Uma única tecnologia no sistema e transações simples.

**Contras:**

- Não atende ao requisito de um banco NoSQL;
- O retrato da OS e o histórico exigiriam colunas JSON ou várias tabelas para algo que é naturalmente um documento.

### 4.3. Redis ou DynamoDB

**Prós:**

- Redis é muito rápido; DynamoDB é gerenciado e escalável.

**Contras:**

- Nenhum dos dois dá, de forma simples, a transação entre a saga e a outbox nem consultas por estado, etapa e período;
- Mais uma tecnologia fora do que a equipe já domina.

## 5. Proposta

Adotar o **MongoDB em replica set**:

| Coleção | Conteúdo |
|---|---|
| `saga_instances` | Uma saga por OS (`_id` = `workOrderId`): estado, retrato da OS, orçamento, compensações pendentes, `version` e histórico embutido |
| `inbox` | Idempotência das mensagens recebidas, por `message-id` |
| `outbox` | Commands a publicar, ordenados por `sequence` |
| `locks` | Lease do publicador da outbox |

O driver usa UUID no formato `standard`. Em desenvolvimento, o `docker-compose` sobe o MongoDB já como replica set de um
nó. Em produção, usa-se um cluster que ofereça replica set (por exemplo, o MongoDB Atlas), informado em `MONGODB_URI`.

## 6. Impacto e Riscos

- **Risco**: subir o MongoDB sem replica set faz as transações falharem.
    - **Mitigação**: o compose já inicia como replica set, e o README documenta a exigência.
- **Risco**: histórico embutido muito grande.
    - **Mitigação**: o histórico tem um registro por evento de uma OS, o que mantém o documento pequeno.

## 7. Implementação

1. Configurar o Spring Data MongoDB com transações (`MongoConfig`) e UUID `standard`.
2. Mapear a saga em `SagaDocument` com `@Version`.
3. Gravar saga e outbox na mesma transação (`ProcessSagaEventUseCase`).
4. Cobrir com testes de integração (Testcontainers com `MongoDBContainer`).

## 8. Decisão Final

| Item           | Detalhe                     |
|----------------|-----------------------------|
| Banco de dados | MongoDB (replica set)       |
| Status         | Proposta — aprovada         |
