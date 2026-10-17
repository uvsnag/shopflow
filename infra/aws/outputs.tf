output "instance_id" {
  description = "EC2 instance id (GitHub var EC2_INSTANCE_ID)."
  value       = aws_instance.app.id
}

output "public_ip" {
  description = "Elastic IP of the demo box (GitHub var DEMO_HOST)."
  value       = aws_eip.app.public_ip
}

output "public_dns" {
  description = "Public DNS name of the instance."
  value       = aws_instance.app.public_dns
}

output "ecr_registry" {
  description = "ECR registry host; images are <registry>/shopflow/<name>:<sha>."
  value       = local.ecr_registry
}

output "rds_endpoint" {
  description = "RDS PostgreSQL endpoint (host:port)."
  value       = aws_db_instance.postgres.endpoint
}

output "deploy_role_arn" {
  description = "IAM role assumed by GitHub Actions via OIDC (GitHub secret AWS_DEPLOY_ROLE_ARN)."
  value       = aws_iam_role.deploy.arn
}

output "demo_urls" {
  value = {
    frontend = "http://${aws_eip.app.public_ip}:3000"
    gateway  = "http://${aws_eip.app.public_ip}:8080/actuator/health"
  }
}

# Copy these into the GitHub repo: Settings -> Secrets and variables -> Actions.
output "github_actions_settings" {
  description = "Repository variables (vars.*) and the one secret (secrets.*) that ci.yml / deploy.yml expect."
  value = {
    vars = {
      AWS_DEPLOY_ENABLED = "true"
      AWS_REGION         = var.region
      EC2_INSTANCE_ID    = aws_instance.app.id
      DEMO_HOST          = aws_eip.app.public_ip
    }
    secrets = {
      AWS_DEPLOY_ROLE_ARN = aws_iam_role.deploy.arn
    }
  }
}
