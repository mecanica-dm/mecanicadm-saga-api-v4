# [006]: [API de consulta da saga protegida por JWT compartilhado]

* **Status**: Aceito
* **Data**: 12/11/2026

## 1. Contexto e Problema

A saga conduz o fluxo só por mensagens, mas **alguém precisa ver o andamento**: quais OS estão pausadas, em que etapa
cada uma está e como chegou ali. Até então, isso exigia consultar o MongoDB à mão.

Ao abrir uma API HTTP, surgem duas perguntas: o que ela expõe e **como se protege**, já que ela mostra dados de OS.

## 2. Decisão

### 2.1 Uma API só de consulta

| Rota | O que faz |
|---|---|
| `GET /api/sagas` | Lista as sagas, com filtros e paginação |
| `GET /api/sagas/{workOrderId}` | Uma saga com o histórico completo |

- A saga é identificada pelo **id da OS** (há uma por OS), então não existe um "id da saga" separado.
- Filtros (todos opcionais, combinados com E): `workOrderId`, `state`, `step` (uma saga pausada conta na etapa em que
  parou), `pausedFrom`, `createdFrom`/`createdTo` e `updatedFrom`/`updatedTo` (ISO-8601).
- Paginação (`page`, `size`, máximo de 100) e ordenação por `createdAt`, `updatedAt`, `state` ou `workOrderId`
  (padrão: `updatedAt` decrescente). Valores de ordenação inválidos caem no padrão.
- A API **não altera nem retoma** sagas: só consulta.
- A listagem devolve um resumo com o nome do cliente e a placa. Não expõe e-mail, documento nem telefone.

### 2.2 Proteção por JWT compartilhado

- A API exige o **mesmo JWT** que a Workorder emite no login, validado com o segredo `JWT_SECRET`, que precisa ser
  igual nos serviços. O issuer é `workorder_api`.
- São públicos apenas o Swagger, o `/error` e o Actuator.
- O filtro, o serviço de token e a configuração seguem os do Finance, para o comportamento ser o mesmo nos serviços.
- Não há perfil que desligue a autenticação: o perfil `ci` do Finance (que libera tudo para o scan de segurança) não foi
  replicado na saga.

## 3. Consequências

### Positivas

- O andamento de cada OS e as pausas ficam consultáveis, o que também ajuda a demonstrar o fluxo.
- Um único login serve para os três serviços, sem outro provedor de identidade.
- A API é simples de proteger porque só lê.

### Negativas / Custos

- O segredo é **compartilhado**: vazar em um serviço compromete todos, e a rotação exige atualizar os três.
- O token atual não tem papéis; qualquer usuário autenticado consulta qualquer saga.
- Para o Swagger, é preciso informar o token manualmente.

## 4. Relação com Outras Decisões

- [ADR 001 - Orquestrador orientado a eventos](001-orquestrador_orientado_a_eventos_por_etapas.md)
