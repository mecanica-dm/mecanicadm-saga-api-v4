# Terraform — MongoDB da Saga API

Infraestrutura do serviço `mecanicadm-saga-api-v4`. A saga usa **MongoDB** (RFC
002 — transações multidocumento entre a saga e a outbox exigem replica set), e
no projeto v4 cada serviço provisiona o próprio banco. Como o MongoDB aqui é o
**MongoDB Atlas** (cluster `M0` free tier), este state **não cria nada na VPC**
— não há RDS, security group nem subnet group, diferente do workorder e do
finance.

## O que é criado

| Recurso | Finalidade |
|---|---|
| `mongodbatlas_project` | Projeto `mecanicadm-prod-saga` na organização do Atlas |
| `mongodbatlas_advanced_cluster` M0 | Replica set gerenciado de 3 nós (`saga-prod`), transações inclusas |
| `mongodbatlas_database_user` | Usuário `saga_admin` com role `readWrite` **apenas** em `saga_db` |
| `mongodbatlas_project_ip_access_list` | CIDRs liberados no projeto (padrão `0.0.0.0/0`) |
| `aws_secretsmanager_secret` do MongoDB | URI, usuário, senha e banco |
| Parâmetros SSM | `mongodb_uri`, `mongodb_username`, `mongodb_password` e `mongodb_db_name` |

A `MONGODB_URI` publicada no SSM já vem completa com credenciais e
`authSource=admin`:

```
mongodb+srv://saga_admin:<senha>@saga-prod.xxxxx.mongodb.net/saga_db?retryWrites=true&w=majority&authSource=admin
```

## O que **não** é criado aqui

- **JWT compartilhado**: já é criado pelo `mecanicadm-workorder-api-v4`
  (`mecanicadm/prod/jwt-secret` no Secrets Manager e `/mecanicadm/prod/jwt_secret`
  no SSM). Workorder, finance e saga precisam validar os mesmos tokens, então
  este state apenas **consome** o segredo existente. Criá-lo nos dois states
  causaria conflito de recurso.
- **RabbitMQ**: o broker é gerenciado no CloudAMQP (plano compartilhado Little
  Lemur) e **não** é criado por este state. As credenciais ficam no SSM em
  `/mecanicadm/prod/rabbitmq/{user,password,vhost}` — publicadas pelas pipelines
  de deploy a partir dos GitHub Secrets `CLOUDAMQP_*` — e são materializadas no
  Secret `mecanicadm-saga-secrets`. O host e a porta (`5671`, TLS) ficam no
  ConfigMap. A topologia completa dos três serviços
  (`messaging/rabbitmq-definitions.json`) é importada **automaticamente** pelo
  `app-deploy.yml` no vhost do CloudAMQP a cada deploy (o `"vhost": "/"` do
  arquivo é ignorado; aplica-se no vhost real). A importação é idempotente.
- **Namespace, Ingress, Kong, Route 53, HPA, image pull**: pertencem ao
  `mecanicadm-infra-v4` e aos manifests em `devops/k8s`.
- **Licença do New Relic**: continua vindo dos GitHub Secrets do workflow de
  deploy.
- **Recursos AWS de rede** (VPC, subnets, SG): o M0 não faz VPC peering nem
  private endpoint, então nada da VPC é usado. A conexão sai do EKS pela
  internet com TLS + usuário/senha.

## Pré-requisitos

1. Organização no MongoDB Atlas com uma **API key** (Access Manager → API Keys
   → Create API Key, permissão de Organization Owner ou Project Owner) e o
   **ID da organização** (Settings → Organization ID).
2. `mecanicadm-workorder-api-v4` aplicado (fornece o JWT compartilhado em
   `/mecanicadm/prod/jwt_secret`).
3. Bucket do state (`mecanicadm-v4-01`) e credenciais AWS com permissão para
   Secrets Manager e SSM (não é preciso RDS nem EC2 neste state).
4. Terraform >= 1.10.

No GitHub, configure:

- Secrets `MONGODB_ATLAS_PUBLIC_KEY` e `MONGODB_ATLAS_PRIVATE_KEY`.
- Variable `MONGODB_ATLAS_ORG_ID` (o ID da organização — não é secreto).

## Backend

O state fica separado do cluster e dos demais serviços:

```hcl
# backend.hcl
bucket       = "mecanicadm-v4-01"
region       = "us-east-1"
key          = "terraform/mecanicadm-saga/terraform.tfstate"
encrypt      = true
use_lockfile = true
```

```bash
terraform init -backend-config=backend.hcl
```

Há um modelo em [`backend.hcl.example`](backend.hcl.example).

## Aplicação

```bash
terraform init -backend-config=backend.hcl
terraform fmt -check -recursive -diff
terraform validate
terraform plan -out=tfplan
terraform apply tfplan
```

Para sobrescrever configurações:

```bash
cp terraform.tfvars.example terraform.tfvars
```

O cluster M0 leva de 7 a 10 minutos para ficar disponível na primeira criação;
o output `mongodb_cluster_hostname` só é preenchido depois disso.

## Aplicação via GitHub Actions (sem apply local)

O workflow [`.github/workflows/terraform.yml`](../../.github/workflows/terraform.yml)
executa `init` → `fmt` → `validate` → `plan` → `apply` no GitHub Actions:

- **Pull request**: apenas `plan` (validação).
- **Push na `main`** que altere `devops/terraform/**`: `apply` automático,
  protegido pelo environment `prod`.
- **workflow_dispatch**: execução manual, útil para o primeiro apply.

Depois que o workflow de Terraform termina com sucesso, o
[`app-deploy.yml`](../../.github/workflows/app-deploy.yml) é disparado via
`workflow_run` e cria os Secrets a partir do SSM antes de aplicar os manifests.

## Como a pipeline consome

O workflow `.github/workflows/app-deploy.yml` cria os Secrets do Kubernetes
diretamente a partir do SSM, na seguinte ordem:

- `mecanicadm-saga-db-secrets`: `MONGODB_URI`, lido de
  `/mecanicadm/prod/saga/mongodb_uri`.
- `mecanicadm-saga-secrets`: `JWT_SECRET`, lido de `/mecanicadm/prod/jwt_secret`,
  `NEW_RELIC_LICENSE_KEY` (GitHub Secrets) e as credenciais do CloudAMQP
  (`RABBITMQ_USER`, `RABBITMQ_PASSWORD` e `RABBITMQ_VHOST`), lidas de
  `/mecanicadm/prod/rabbitmq/*`. Antes de montar o Secret, a pipeline publica
  nesse prefixo os parâmetros que ainda não existirem, usando os GitHub Secrets
  `CLOUDAMQP_USER`, `CLOUDAMQP_PASSWORD` e `CLOUDAMQP_VHOST` deste repositório —
  ou seja, não depende de o finance-api ou o workorder terem feito deploy antes.
  Se os parâmetros já existirem, os valores atuais são mantidos (o finance os
  sobrescreve no deploy dele).

Os comandos equivalentes para depuração manual são:

```bash
MONGODB_URI=$(aws ssm get-parameter --name /mecanicadm/prod/saga/mongodb_uri --with-decryption --query Parameter.Value --output text)
MONGODB_USER=$(aws ssm get-parameter --name /mecanicadm/prod/saga/mongodb_username --with-decryption --query Parameter.Value --output text)
MONGODB_PASSWORD=$(aws ssm get-parameter --name /mecanicadm/prod/saga/mongodb_password --with-decryption --query Parameter.Value --output text)
MONGODB_DB_NAME=$(aws ssm get-parameter --name /mecanicadm/prod/saga/mongodb_db_name --query Parameter.Value --output text)
JWT_SECRET=$(aws ssm get-parameter --name /mecanicadm/prod/jwt_secret --with-decryption --query Parameter.Value --output text)
RABBITMQ_USER=$(aws ssm get-parameter --name /mecanicadm/prod/rabbitmq/user --with-decryption --query Parameter.Value --output text)
RABBITMQ_PASSWORD=$(aws ssm get-parameter --name /mecanicadm/prod/rabbitmq/password --with-decryption --query Parameter.Value --output text)
RABBITMQ_VHOST=$(aws ssm get-parameter --name /mecanicadm/prod/rabbitmq/vhost --with-decryption --query Parameter.Value --output text)
```

Os valores precisam bater com o que a aplicação espera (`MONGODB_URI`,
`JWT_SECRET`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` e `RABBITMQ_VHOST`).

## Custos, limites e destruição

- O **M0 é gratuito** (não entra no free trial de 30 dias): 512 MB de
  armazenamento, RAM/CPU compartilhados e **sem backups automáticos**. Para o
  ambiente de estudo é suficiente — o estado da saga é reconstituível a partir
  dos eventos da Workorder e do Finance.
- Sem o M0 não há VPC peering: a IP Access List fica `0.0.0.0/0`, protegida
  por senha alfanumérica de 32 caracteres gerada pelo Terraform e tráfego TLS
  (o driver só fala `mongodb+srv`). Restrinja `atlas_ip_access_list` se os
  nodes do EKS saírem por IPs fixos.
- `terraform destroy` **apaga o cluster e os dados** (o M0 não tem snapshot
  final). O parâmetro `mongodb_uri` do SSM e o secret do Secrets Manager são
  removidos junto; com `secret_recovery_window_in_days = 0` o secret volta a
  ser criável imediatamente. Em produção real, exporte os dados antes
  (`mongodump`) e use 7–30 dias de janela de recuperação.
- Para upgrade: M0 → M10+ troca o `provider_name` de `TENANT` para `AWS` e
  remove `backing_provider_name` (com custo por hora). No
  `mongodbatlas_advanced_cluster` o upgrade é aplicado sozinho; outras
  mudanças no mesmo apply devem esperar um segundo apply. Consulte a doc do
  provider antes de mexer.
