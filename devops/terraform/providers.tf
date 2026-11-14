terraform {
  # >= 1.10 para suportar o lock nativo do backend S3 (use_lockfile).
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    mongodbatlas = {
      source  = "mongodb/mongodbatlas"
      version = "~> 2.0"
    }
  }

  # A configuração do backend é passada no `terraform init`
  # (veja o arquivo backend.hcl.example).
  backend "s3" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.common_tags
  }
}

# O MongoDB (RFC 002) fica no MongoDB Atlas, não na AWS. Este provider usa as
# chaves de API da organização (Access Manager -> API Keys -> Create API Key,
# escopo de Organization Owner ou Project Owner). Os valores vêm das variáveis
# atlas_public_key/atlas_private_key, alimentadas pelo workflow pelo TF_VAR_*.
provider "mongodbatlas" {
  public_key  = var.atlas_public_key
  private_key = var.atlas_private_key
}
