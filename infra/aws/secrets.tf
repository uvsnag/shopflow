# Runtime secrets live in Secrets Manager (ADR 0010 non-negotiable); the instance reads them at boot
# and writes /opt/shopflow/.env. Nothing secret is stored in GitHub.

# RS256 signing key for auth-service (NFR-07: stable key so tokens survive restarts). PKCS#8 is what
# JwtService expects ("-----BEGIN PRIVATE KEY-----").
resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

# Shared HMAC secret between payment-service and the fake payment provider (webhook signatures).
resource "random_password" "webhook_secret" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "${var.project}/app"
  description             = "ShopFlow runtime secrets consumed by the EC2 user-data script"
  recovery_window_in_days = 0 # demo: allow immediate re-create after destroy
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    DB_PASSWORD         = random_password.db_master.result
    JWT_PRIVATE_KEY_PEM = tls_private_key.jwt.private_key_pem_pkcs8
    WEBHOOK_SECRET      = random_password.webhook_secret.result
  })
}
