# ---------------------------------------------------------------------------
# Senha do usuário do Atlas
#
# Alfanumérica de propósito: a senha entra literalmente na MONGODB_URI
# publicada no SSM, e caracteres especiais exigiriam percent-encoding (um
# ":" ou "@" quebraria a URI silenciosamente).
# ---------------------------------------------------------------------------
resource "random_password" "mongodb_password" {
  length      = 32
  special     = false
  min_upper   = 1
  min_lower   = 1
  min_numeric = 1

  keepers = {
    environment = var.environment
    service     = var.service_name
    username    = var.mongodb_username
  }
}

# ---------------------------------------------------------------------------
# Secrets Manager — credenciais do MongoDB
# ---------------------------------------------------------------------------
resource "aws_secretsmanager_secret" "mongodb" {
  name                    = "mecanicadm/${var.environment}/${var.service_name}/mongodb-credentials"
  description             = "Credenciais do MongoDB Atlas do servico ${var.service_name} (${var.environment})."
  recovery_window_in_days = var.secret_recovery_window_in_days

  tags = {
    Name = "${local.atlas_cluster_name}-mongodb-credentials"
  }
}

resource "aws_secretsmanager_secret_version" "mongodb" {
  secret_id = aws_secretsmanager_secret.mongodb.id

  secret_string = jsonencode({
    uri        = local.mongodb_uri
    username   = var.mongodb_username
    password   = random_password.mongodb_password.result
    database   = var.mongodb_db_name
    authSource = "admin"
    cluster    = mongodbatlas_advanced_cluster.main.name
    projectId  = mongodbatlas_project.main.id
  })
}

# ---------------------------------------------------------------------------
# Secrets Manager — JWT compartilhado entre os serviços da saga
#
# O JWT NÃO é criado aqui: ele pertence ao state do
# mecanicadm-workorder-api-v4 (mecanicadm/<env>/jwt-secret e o parâmetro SSM
# /mecanicadm/<env>/jwt_secret). Workorder, finance e saga precisam validar os
# mesmos tokens, então apenas consumimos o recurso existente. Criá-lo em dois
# states causaria conflito.
# ---------------------------------------------------------------------------
data "aws_ssm_parameter" "jwt_secret" {
  name            = var.jwt_secret_ssm_parameter_name
  with_decryption = true
}

data "aws_secretsmanager_secret" "jwt" {
  name = "mecanicadm/${var.environment}/jwt-secret"
}

# ---------------------------------------------------------------------------
# SSM — consumido pela pipeline para montar o Secret do Kubernetes.
#
# A aplicação espera exatamente: MONGODB_URI, JWT_SECRET, RABBITMQ_USER,
# RABBITMQ_PASSWORD e RABBITMQ_VHOST. A URI com credenciais fica em
# mongodb_uri; usuário e senha também são publicados separados para
# depuração/rotação.
# ---------------------------------------------------------------------------
resource "aws_ssm_parameter" "mongodb_uri" {
  name  = "${local.ssm_prefix}/mongodb_uri"
  type  = "SecureString"
  value = local.mongodb_uri

  tags = {
    Name = "${local.atlas_cluster_name}-mongodb-uri"
  }
}

resource "aws_ssm_parameter" "mongodb_username" {
  name  = "${local.ssm_prefix}/mongodb_username"
  type  = "SecureString"
  value = var.mongodb_username

  tags = {
    Name = "${local.atlas_cluster_name}-mongodb-username"
  }
}

resource "aws_ssm_parameter" "mongodb_password" {
  name  = "${local.ssm_prefix}/mongodb_password"
  type  = "SecureString"
  value = random_password.mongodb_password.result

  tags = {
    Name = "${local.atlas_cluster_name}-mongodb-password"
  }
}

resource "aws_ssm_parameter" "mongodb_db_name" {
  name  = "${local.ssm_prefix}/mongodb_db_name"
  type  = "String"
  value = var.mongodb_db_name

  tags = {
    Name = "${local.atlas_cluster_name}-mongodb-db-name"
  }
}
