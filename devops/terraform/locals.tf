locals {
  name_prefix = "mecanicadm-${var.environment}"

  # Projeto dedicado ao serviço (prática recomendada do Atlas: projeto por
  # aplicação/ambiente). Se a organização já tiver um projeto com esse nome,
  # importe-o em vez de criar outro:
  #   terraform import mongodbatlas_project.main <project_id>
  atlas_project_name = "${local.name_prefix}-${var.service_name}"

  # Mesma convenção do identificador dos bancos RDS dos outros serviços:
  #   mecanicadm-<env>-<service> -> aqui saga-<env> (curto, entra no hostname)
  atlas_cluster_name = "${var.service_name}-${var.environment}"

  ssm_prefix = "/mecanicadm/${var.environment}/${var.service_name}"

  # URI pronta para a aplicação (spring.data.mongodb.uri). A senha é gerada
  # alfanumérica (random_password com special = false), então não precisa de
  # percent-encoding na URI. O authSource é "admin" porque o Atlas cria os
  # usuários lá, mesmo com role só no banco da saga.
  mongodb_uri = format(
    "mongodb+srv://%s:%s@%s/%s?retryWrites=true&w=majority&authSource=admin",
    var.mongodb_username,
    random_password.mongodb_password.result,
    trimprefix(mongodbatlas_advanced_cluster.main.connection_strings.standard_srv, "mongodb+srv://"),
    var.mongodb_db_name,
  )

  common_tags = {
    Project     = "mecanicadm"
    Environment = var.environment
    Service     = var.service_name
    ManagedBy   = "terraform"
  }
}
