variable "region" {
  description = "Região AWS usada para o SSM e o Secrets Manager (deve ser a mesma do cluster EKS). O MongoDB fica no Atlas, fora da AWS."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Ambiente. O projeto v4 suporta apenas produção."
  type        = string
  default     = "prod"

  validation {
    condition     = var.environment == "prod"
    error_message = "Apenas o ambiente de produção (prod) é suportado."
  }
}

variable "service_name" {
  description = "Nome do serviço usado nos nomes dos recursos e no prefixo dos parâmetros SSM."
  type        = string
  default     = "saga"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.service_name))
    error_message = "Use apenas letras minúsculas, números e hífens."
  }
}

# ---------------------------------------------------------------------------
# MongoDB Atlas (RFC 002: MongoDB em replica set)
# ---------------------------------------------------------------------------

variable "atlas_org_id" {
  description = "ID da organização do MongoDB Atlas onde o projeto será criado (Atlas: Settings -> Organization)."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]{24}$", var.atlas_org_id))
    error_message = "atlas_org_id deve ser o ID de 24 caracteres hexadecimais mostrado na página da organização no Atlas."
  }
}

variable "atlas_public_key" {
  description = "Public key da API key do MongoDB Atlas usada pelo provider."
  type        = string
  sensitive   = true
}

variable "atlas_private_key" {
  description = "Private key da API key do MongoDB Atlas usada pelo provider."
  type        = string
  sensitive   = true
}

variable "atlas_region" {
  description = "Região do cluster M0 no Atlas, no formato do Atlas (ex.: US_EAST_1). Deve ser a mesma região da API para latência baixa."
  type        = string
  default     = "US_EAST_1"
}

variable "mongodb_version" {
  description = "Versão major do MongoDB do cluster (ex.: 7.0). O compose local usa mongo:7.0; mantenha as duas iguais."
  type        = string
  default     = "7.0"
}

variable "mongodb_db_name" {
  description = "Nome do banco usado pela saga (precisa bater com o banco da MONGODB_URI da aplicação)."
  type        = string
  default     = "saga_db"

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9_]*$", var.mongodb_db_name))
    error_message = "O nome do banco deve começar com letra e conter apenas letras, números e underscore."
  }
}

variable "mongodb_username" {
  description = "Usuário da aplicação no Atlas. É criado no authSource admin com role readWrite apenas no banco da saga."
  type        = string
  default     = "saga_admin"

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9_]*$", var.mongodb_username))
    error_message = "O usuário deve começar com letra e conter apenas letras, números e underscore (sem caracteres especiais: entra na URI)."
  }
}

variable "atlas_ip_access_list" {
  description = "CIDRs liberados no IP Access List do projeto Atlas. O M0 não aceita VPC peering, então o padrão é 0.0.0.0/0 protegido por senha forte; restrinja se tiver IPs fixos."
  type        = list(string)
  default     = ["0.0.0.0/0"]

  validation {
    condition     = length(var.atlas_ip_access_list) > 0
    error_message = "Informe pelo menos um CIDR no IP Access List do Atlas."
  }

  validation {
    condition     = alltrue([for cidr in var.atlas_ip_access_list : can(cidrhost(cidr, 0))])
    error_message = "atlas_ip_access_list deve conter apenas blocos CIDR válidos."
  }
}

# ---------------------------------------------------------------------------
# Secrets
# ---------------------------------------------------------------------------

variable "secret_recovery_window_in_days" {
  description = "Janela de recuperação dos secrets no Secrets Manager (0 = exclusão imediata, útil para destroy/recreate)."
  type        = number
  default     = 0

  validation {
    condition     = var.secret_recovery_window_in_days == 0 || (var.secret_recovery_window_in_days >= 7 && var.secret_recovery_window_in_days <= 30)
    error_message = "Use 0 para exclusão imediata ou um valor entre 7 e 30 dias."
  }
}

variable "jwt_secret_ssm_parameter_name" {
  description = "Parâmetro SSM do JWT compartilhado entre os serviços v4. O segredo é criado pelo mecanicadm-workorder-api-v4; aqui apenas consumimos."
  type        = string
  default     = "/mecanicadm/prod/jwt_secret"

  validation {
    condition     = startswith(var.jwt_secret_ssm_parameter_name, "/")
    error_message = "O nome do parâmetro SSM deve começar com '/'."
  }
}
