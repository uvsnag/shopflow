variable "region" {
  description = "AWS region for everything."
  type        = string
  default     = "ap-southeast-1"
}

variable "project" {
  description = "Name prefix for all resources and the ECR namespace (shopflow/<image>)."
  type        = string
  default     = "shopflow"
}

variable "github_repo" {
  description = "GitHub repository (owner/name) trusted by the OIDC deploy role and cloned onto the instance."
  type        = string
}

variable "git_branch" {
  description = "Branch whose CI runs may deploy, and which the instance clones at boot."
  type        = string
  default     = "main"
}

variable "instance_type" {
  description = "EC2 instance type. 7 JVMs + Kafka + Redis + the observability stack need ~8 GB RAM, so t3.large (2 vCPU / 8 GB) is the practical minimum; t3.medium (4 GB) swaps itself to death."
  type        = string
  default     = "t3.large"
}

variable "allowed_cidr" {
  description = "CIDR allowed to reach the demo (ports 80, 3000, 8080). Narrow it to your own IP (e.g. 1.2.3.4/32) when not demoing publicly."
  type        = string
  default     = "0.0.0.0/0"
}

variable "db_instance_class" {
  description = "RDS instance class. db.t3.micro is free tier for the first 12 months."
  type        = string
  default     = "db.t3.micro"
}

variable "db_username" {
  description = "RDS master user; the services connect with it (DB_USER)."
  type        = string
  default     = "shopflow"
}

variable "service_databases" {
  description = "Databases created on RDS by the instance user-data script (one per service, ADR 0002). Not managed by Terraform: RDS only creates a single db_name and the list may grow with new services."
  type        = list(string)
  default     = ["auth_db", "product_db", "inventory_db", "order_db", "payment_db", "notification_db"]
}

variable "budget_limit_usd" {
  description = "Monthly AWS Budget limit in USD; alerts at 80% and 100%."
  type        = number
  default     = 10
}

variable "alert_email" {
  description = "Email that receives budget alerts."
  type        = string
}
