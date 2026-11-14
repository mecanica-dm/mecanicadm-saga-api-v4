output "atlas_project_id" {
  description = "ID do projeto criado no MongoDB Atlas."
  value       = mongodbatlas_project.main.id
}

output "mongodb_cluster_name" {
  description = "Nome do cluster M0 no Atlas."
  value       = mongodbatlas_advanced_cluster.main.name
}

output "mongodb_cluster_hostname" {
  description = "Host do cluster (sem credenciais), útil para conferir no console do Atlas."
  value       = mongodbatlas_advanced_cluster.main.connection_strings.standard_srv
}

output "mongodb_uri" {
  description = "URI de conexão completa (com credenciais). O mesmo valor publicado no parâmetro SSM <prefixo>/mongodb_uri."
  value       = local.mongodb_uri
  sensitive   = true
}

output "mongodb_secretsmanager_secret_arn" {
  description = "ARN do secret com as credenciais do MongoDB."
  value       = aws_secretsmanager_secret.mongodb.arn
}

output "jwt_secretsmanager_secret_arn" {
  description = "ARN do secret do JWT compartilhado (criado pelo mecanicadm-workorder-api-v4)."
  value       = data.aws_secretsmanager_secret.jwt.arn
}

output "jwt_secret_ssm_parameter_name" {
  description = "Nome do parâmetro SSM do JWT compartilhado consumido pela pipeline."
  value       = data.aws_ssm_parameter.jwt_secret.name
}

output "ssm_parameter_prefix" {
  description = "Prefixo dos parâmetros SSM consumidos pela pipeline."
  value       = local.ssm_prefix
}
