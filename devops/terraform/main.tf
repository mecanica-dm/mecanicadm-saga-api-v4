# ---------------------------------------------------------------------------
# Projeto do MongoDB Atlas
# Um projeto por serviço/ambiente. Agrupa o cluster, o usuário da aplicação e a
# IP Access List do saga.
# ---------------------------------------------------------------------------
resource "mongodbatlas_project" "main" {
  name   = local.atlas_project_name
  org_id = var.atlas_org_id
}

# ---------------------------------------------------------------------------
# Cluster M0 (free tier) — RFC 002
# Replica set gerenciado de 3 nós: as transações multidocumento (saga + outbox
# na mesma transação) funcionam sem nada extra. O M0 é o análogo do CloudAMQP
# Little Lemur dos outros serviços: free tier compartilhado, sem SLA e sem
# backups automáticos — suficiente para o ambiente de estudo.
#
# Usamos o `mongodbatlas_advanced_cluster` (o `mongodbatlas_cluster` está
# deprecado no provider 2.x). Para o M0 o provider_name é TENANT com backing na
# AWS, no formato do exemplo oficial "Tenant Cluster".
#
# Limitações do M0 (aceitas): 512 MB de armazenamento, sem backup contínuo,
# sem VPC peering (por isso a IP Access List aberta) e upgrade para M10+ gera
# custo por hora.
# ---------------------------------------------------------------------------
resource "mongodbatlas_advanced_cluster" "main" {
  project_id   = mongodbatlas_project.main.id
  name         = local.atlas_cluster_name
  cluster_type = "REPLICASET"

  replication_specs = [
    {
      region_configs = [
        {
          electable_specs = {
            instance_size = "M0"
          }
          provider_name         = "TENANT"
          backing_provider_name = "AWS"
          region_name           = var.atlas_region
          priority              = 7
        }
      ]
    }
  ]
}

# ---------------------------------------------------------------------------
# Usuário da aplicação
# Criado no authSource "admin" com role readWrite apenas no banco da saga —
# sem acesso a admin/local/config nem a outros bancos do projeto.
# ---------------------------------------------------------------------------
resource "mongodbatlas_database_user" "main" {
  project_id         = mongodbatlas_project.main.id
  username           = var.mongodb_username
  password           = random_password.mongodb_password.result
  auth_database_name = "admin"

  roles {
    role_name     = "readWrite"
    database_name = var.mongodb_db_name
  }
}

# ---------------------------------------------------------------------------
# IP Access List
# O M0 não oferece VPC peering nem private endpoint, então a aplicação no EKS
# acessa pela internet, autenticada pelo usuário/senha acima (TLS embutido na
# URI mongodb+srv). O padrão é 0.0.0.0/0; se os nodes do EKS saírem por IPs
# fixos (NAT gateways com Elastic IP), restrinja o CIDR em terraform.tfvars.
# ---------------------------------------------------------------------------
resource "mongodbatlas_project_ip_access_list" "main" {
  for_each = toset(var.atlas_ip_access_list)

  project_id = mongodbatlas_project.main.id
  cidr_block = each.value
  comment    = "Acesso da Saga API (${var.environment}) no EKS"
}
