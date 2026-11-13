# RFC 001 - Escolha do Padrão Saga: Orquestrado ou Coreografado

* **Status**: Aprovado
* **Data**: 12/11/2026
* **Autores**: Time de desenvolvimento
* **Resumo**: Este documento propõe conduzir as transações distribuídas das ordens de serviço com uma **saga
  orquestrada**, em um serviço próprio (Saga API), em vez de uma saga coreografada entre a Workorder e o Finance.

---

## 1. Resumo

O Tech Challenge da Fase 4 exige o **Saga Pattern** para coordenar as transações críticas (abertura da OS, orçamento,
aprovação, execução e pagamento), com compensação em caso de falha, e pede que a escolha entre orquestração e
coreografia seja documentada e justificada. Recomendamos a **orquestração**, com um serviço dedicado.

## 2. Contexto

O fluxo de uma OS passa por dois serviços de negócio e tem várias saídas além do caminho feliz:

- o cliente pode **aprovar, rejeitar ou pedir alterações** no orçamento (e a OS volta a ser editada);
- a OS pode ser **alterada ou cancelada em qualquer fase**, inclusive depois de haver orçamento, link de pagamento ou
  pagamento confirmado;
- o **pagamento pode ser feito depois** de a OS ser cancelada;
- cada serviço pode **recusar** um pedido por regra de negócio ou falhar tecnicamente.

Cada passo concluído tem um efeito que precisa ser desfeito se o fluxo for cancelado depois (orçamento, link, estorno).

## 3. Objetivos

- Ter o fluxo inteiro e as compensações em **um só lugar**, legíveis e testáveis.
- Manter a Workorder e o Finance **sem conhecer um ao outro**.
- Saber, a qualquer momento, **em que passo cada OS está** e por que parou.
- Permitir mudar o fluxo sem alterar os serviços de negócio.

## 4. Alternativas Consideradas

### 4.1. Saga orquestrada em um serviço dedicado (Saga API)

**Prós:**

- O fluxo, as guardas e as compensações ficam num único módulo, com testes que cobrem cada transição;
- A Workorder e o Finance só publicam o que aconteceu e executam commands; não há dependência entre eles;
- O estado de cada OS é uma consulta simples (uma saga por OS, com histórico embutido);
- Compensar "da etapa mais avançada para a mais antiga" é uma regra central, não espalhada;
- Adicionar um passo é declarar uma regra (e uma compensação, se houver efeito a desfazer).

**Contras:**

- Mais um serviço para operar, com banco e fila próprios;
- O orquestrador conhece o fluxo inteiro e vira ponto único de falha lógico (mitigado pelo inbox, pela outbox e por
  poder rodar com mais de uma instância);
- Uma mudança no contrato de um serviço exige mudar a saga.

### 4.2. Saga coreografada (eventos entre Workorder e Finance)

**Prós:**

- Sem serviço extra e sem ponto central;
- Baixo acoplamento em tempo de execução.

**Contras:**

- O fluxo fica **implícito**, espalhado nos listeners dos dois serviços; entender "o que acontece se o cliente pagar
  depois do cancelamento" exige ler os dois códigos;
- Para compensar, cada serviço precisaria conhecer o que o outro fez, o que cria acoplamento por eventos e risco de
  **ciclos** (A reage a B, que reage a A);
- Saber em que passo uma OS está exige reconstruir o estado a partir de eventos espalhados;
- Mudar o fluxo obriga a alterar e implantar os dois serviços de forma coordenada.

### 4.3. Orquestração dentro de um dos serviços (por exemplo, na Workorder)

**Prós:**

- Sem serviço novo.

**Contras:**

- Mistura a regra da OS com a regra do fluxo distribuído e faz a Workorder conhecer o Finance;
- Contraria o objetivo de serviços independentes e especializados.

## 5. Proposta

Adotar a **saga orquestrada** em um serviço dedicado, a **Saga API**:

| Aspecto | Decisão |
|---|---|
| Quem conduz | Saga API: escuta eventos da Workorder e do Finance e responde com commands |
| Instâncias | Uma saga por OS, identificada pelo `workOrderId` |
| Estado | `SagaState`, agrupado em `SagaStep` (registro, orçamento, decisão, execução, pagamento, entrega) |
| Compensação | Declarada por etapa, emitida da mais avançada para a mais antiga e confirmada por evento (ADR 002) |
| Comunicação | Mensageria assíncrona (RabbitMQ), com outbox e inbox (ADR 003) |
| Serviços de negócio | Só emitem eventos do seu domínio e executam commands; recusam por evento (ADR 005) |

## 6. Impacto e Riscos

- **Risco**: a saga é um ponto central de falha lógica.
    - **Mitigação**: inbox idempotente, outbox transacional, lock otimista e DLQ; instâncias adicionais não duplicam
      efeitos.
- **Risco**: mudanças nos contratos dos serviços exigem mudar a saga.
    - **Mitigação**: contratos definidos pelo consumidor (ADR 004) e testes de contrato dos dois lados.
- **Risco**: o orquestrador acumular regra de negócio dos outros serviços.
    - **Mitigação**: a saga só decide o **próximo passo**; a regra de cada passo continua no serviço dono.

## 7. Implementação

1. Modelar o fluxo em `core/saga/flow` (uma classe por etapa) sobre o motor genérico de `core/saga/engine`.
2. Conectar a saga às filas `saga.workorder-events` e `saga.finance-events` e publicar na exchange `saga-output`.
3. Persistir a saga e a outbox no MongoDB, na mesma transação (RFC 002).
4. Cobrir cada transição e cada compensação com testes.

## 8. Decisão Final

| Item           | Detalhe                                 |
|----------------|-----------------------------------------|
| Padrão de saga | Orquestrada, em serviço dedicado        |
| Status         | Proposta — aprovada                     |
