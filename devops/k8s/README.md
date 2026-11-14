# Kubernetes — Saga API

Manifests do serviço `mecanicadm-saga-api-v4` aplicados no namespace
`mecanicadm` do cluster EKS criado pelo `mecanicadm-infra-v4`.

O deploy é feito pelo workflow
[`.github/workflows/app-deploy.yml`](../../.github/workflows/app-deploy.yml).
Nada aqui precisa ser aplicado manualmente em produção.

## Arquivos

| Arquivo | Recurso | Nome |
|---|---|---|
| `serviceaccount.yaml` | ServiceAccount | `mecanicadm-saga` |
| `configmap.yaml` | ConfigMap | `mecanicadm-saga-config` |
| `service.yaml` | Service (ClusterIP) | `mecanicadm-saga` |
| `deployment.yaml` | Deployment | `mecanicadm-saga` |
| `hpa.yaml` | HorizontalPodAutoscaler | `mecanicadm-saga` |
| `ingress.yaml` | Ingress (Kong) | `mecanicadm-saga` |

## Por que os nomes têm o sufixo `saga`

O workorder usa `mecanicadm`, `mecanicadm-config` e `mecanicadm-secrets` e o
finance usa `mecanicadm-finance*` no mesmo namespace. Para os três serviços
conviverem sem colisão, todos os recursos do saga usam `mecanicadm-saga*`.

## Por que não há Job de seed

O banco da saga é o MongoDB Atlas (RFC 002). As coleções (`saga_instances`,
`inbox`, `outbox`, `locks`) e os índices são criados pela própria aplicação
(`auto-index-creation` + `@Indexed`), e não existe dado de exemplo para
popular: as sagas nascem dos eventos publicados pela Workorder e pela Finance.
Por isso não há `seed-job.yaml` nem `seed/init.sql` neste diretório, diferente
do workorder e do finance.

## Secrets

Não há credencial versionada. Na pipeline:

1. `mecanicadm-saga-db-secrets` é montado a partir dos parâmetros SSM
   publicados pelo Terraform (`/mecanicadm/prod/saga/mongodb_*`). A única
   chave é `MONGODB_URI` — a URI completa do Atlas com credenciais e
   `authSource=admin` (o Spring resolve host, porta, TLS e replica set pelo
   `mongodb+srv`).
2. `mecanicadm-saga-secrets` é montado com:
   - `JWT_SECRET` → `/mecanicadm/prod/jwt_secret` (criado pelo
     `mecanicadm-workorder-api-v4`, compartilhado entre os serviços);
   - `RABBITMQ_USER`, `RABBITMQ_PASSWORD` e `RABBITMQ_VHOST` →
     `/mecanicadm/prod/rabbitmq/*`, publicados pela própria pipeline a partir
     dos GitHub Secrets `CLOUDAMQP_*` (apenas os parâmetros ausentes; o
     finance sobrescreve no deploy dele);
   - `NEW_RELIC_LICENSE_KEY` → GitHub Secret do repositório.

O agente do New Relic já está embutido na imagem (`newrelic/newrelic.jar`) com
`app_name` `mecanicadm-saga-api` definido no `newrelic/newrelic.yml`.

## Pré-requisitos no GitHub

Antes do primeiro deploy, o repositório precisa de:

**Secrets**

| Secret | Usado por |
|---|---|
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | `terraform.yml` e `app-deploy.yml` |
| `DOCKER_USERNAME`, `DOCKER_PASSWORD` | `ci-cd.yml` (push da imagem) |
| `SONAR_TOKEN` | `ci-cd.yml` (Sonar) |
| `NEW_RELIC_LICENSE_KEY` | `app-deploy.yml` → Secret da aplicação |
| `CLOUDAMQP_USER`, `CLOUDAMQP_PASSWORD`, `CLOUDAMQP_VHOST` | `app-deploy.yml` → SSM `/mecanicadm/prod/rabbitmq/*` → Secret da aplicação |
| `MONGODB_ATLAS_PUBLIC_KEY`, `MONGODB_ATLAS_PRIVATE_KEY` | `terraform.yml` (API key do Atlas) |

**Variables**

| Variable | Padrão | Uso |
|---|---|---|
| `AWS_REGION` | `us-east-1` | Região do Terraform/EKS |
| `EKS_CLUSTER_NAME` | `mecanicadm-prod` | Nome do cluster |
| `DOCKER_IMAGE_REPO` | `guilhermemuchon/mecanica-dm-saga-api` | Repositório da imagem |
| `TF_STATE_BUCKET` | `mecanicadm-v4-01` | Bucket do state do Terraform |
| `MONGODB_ATLAS_ORG_ID` | — | ID da organização do Atlas (`terraform.yml`) |

**Environment** `prod` criado no repositório (é o `environment` dos jobs de
apply e deploy).

## Ingress

`https://saga.mecanicadm.com.br/api` → Kong → Service `mecanicadm-saga`.

O host é próprio do saga porque `api.mecanicadm.com.br` já pertence ao
workorder e `finance.mecanicadm.com.br` ao finance. O ExternalDNS cria o
registro no Route 53 a partir do Ingress. As consultas (`GET /api/sagas`)
exigem o JWT emitido pela Workorder; Swagger e Actuator são públicos.

## Imagem

O Deployment traz `guilhermemuchon/mecanica-dm-saga-api:prod-latest` apenas
como placeholder. A pipeline sempre roda `kubectl set image` com
`vars.DOCKER_IMAGE_REPO` e a tag imutável `prod-<sha>` do build.

> **Atenção:** a variável de repositório `DOCKER_IMAGE_REPO` do saga precisa
> apontar para um repositório Docker Hub **próprio do serviço**. O `ci-cd.yml`
> também publica a tag móvel `prod-latest`; se os três serviços usarem o mesmo
> repositório, um sobrescreve a tag do outro. Os deploys automáticos usam
> `prod-<sha>` e não são afetados, mas deploys manuais com `prod-latest` podem
> subir a imagem errada.

## RabbitMQ (CloudAMQP)

O broker é uma instância compartilhada do CloudAMQP (Little Lemur) em
`us-east-1`, usada também pelo workorder e pelo finance.

- `RABBITMQ_HOST` / `RABBITMQ_PORT` (`5671`, TLS) / `RABBITMQ_SSL_ENABLED` ficam
  no `configmap.yaml`.
- `RABBITMQ_USER` / `RABBITMQ_PASSWORD` / `RABBITMQ_VHOST` vêm do SSM
  (`/mecanicadm/prod/rabbitmq/*`) e entram no Secret
  `mecanicadm-saga-secrets` pela pipeline.
- O vhost do plano compartilhado **não** é `/`: use exatamente o valor mostrado
  no console do CloudAMQP.
- A topologia completa dos **três serviços** (exchanges, filas e bindings com
  DLQs) está em `messaging/rabbitmq-definitions.json` deste repositório e é
  aplicada no vhost do CloudAMQP pelo `app-deploy.yml` a cada deploy, via
  `devops/scripts/rabbitmq-apply-topology.sh` (recurso por recurso, idempotente
  e sem remover nada). O deploy do saga garante sozinho a topologia inteira do
  sistema; os deploys do workorder e do finance reaplicam as suas partes.

O readiness do pod fica em `readinessState,mongo`
(`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE`) para o RabbitMQ fora do
ar não impedir o pod de ficar pronto (o listener reconecta sozinho).
