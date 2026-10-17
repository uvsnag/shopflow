# One Amazon Linux 2023 box running docker compose (ADR 0010, option A).
data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64"
}

# Elastic IP created before the instance so user-data can bake DEMO_HOST in, and so the address
# survives stop/start (the frontend image has the gateway URL compiled in, so it must not change).
resource "aws_eip" "app" {
  domain = "vpc"
  tags   = { Name = "${var.project}-app" }
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023_ami.value
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.instance.name

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
    encrypted   = true
  }

  metadata_options {
    http_tokens = "required" # IMDSv2 only
  }

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    region       = var.region
    github_repo  = var.github_repo
    git_branch   = var.git_branch
    ecr_registry = local.ecr_registry
    secret_id    = aws_secretsmanager_secret.app.name
    db_host      = aws_db_instance.postgres.address
    db_user      = var.db_username
    databases    = var.service_databases
    demo_host    = aws_eip.app.public_ip
  })
  # The script is first-boot only; set to true if you want a changed script to replace the box.
  user_data_replace_on_change = false

  depends_on = [aws_secretsmanager_secret_version.app]

  tags = { Name = "${var.project}-app" }

  lifecycle {
    ignore_changes = [ami] # the SSM parameter moves with every AL2023 release; don't replace the box
  }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}
