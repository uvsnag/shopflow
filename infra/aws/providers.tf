# ShopFlow on AWS, plan option A (ADR 0010): one EC2 box running docker compose + RDS PostgreSQL.
terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # Local state is fine for a one-person demo. To share state, replace with:
  #   backend "s3" { bucket = "<your-bucket>", key = "shopflow/terraform.tfstate", region = "ap-southeast-1", use_lockfile = true }
  # then run `terraform init -migrate-state`.
  backend "local" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id   = data.aws_caller_identity.current.account_id
  ecr_registry = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com"

  # One ECR repository per image built by .github/workflows/ci.yml.
  images = [
    "api-gateway",
    "auth-service",
    "product-service",
    "inventory-service",
    "order-service",
    "payment-service",
    "notification-service",
    "frontend",
    "fake-payment-provider",
  ]
}
