# Default VPC keeps this cheap (no NAT gateway); the SG is the only perimeter.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# No SSH: the box is managed through SSM Session Manager / Run Command only.
resource "aws_security_group" "app" {
  name        = "${var.project}-app"
  description = "ShopFlow demo instance: frontend, gateway and plain HTTP"
  vpc_id      = data.aws_vpc.default.id

  dynamic "ingress" {
    for_each = { http = 80, frontend = 3000, gateway = 8080 }
    content {
      description = ingress.key
      from_port   = ingress.value
      to_port     = ingress.value
      protocol    = "tcp"
      cidr_blocks = [var.allowed_cidr]
    }
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# RDS only answers the instance, never the internet.
resource "aws_security_group" "rds" {
  name        = "${var.project}-rds"
  description = "ShopFlow RDS: PostgreSQL from the app instance only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "postgres from app instance"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_db_subnet_group" "default" {
  name       = "${var.project}-default"
  subnet_ids = data.aws_subnets.default.ids
}
