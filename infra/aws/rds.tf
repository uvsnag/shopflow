# RDS PostgreSQL 16 on the free-tier class. The six per-service databases (var.service_databases)
# are created by the instance user-data script with psql, not here: RDS can only create one db_name.
resource "random_password" "db_master" {
  length           = 32
  special          = true
  override_special = "!#$%^&*()-_=+[]{}<>:?" # RDS forbids / @ " and space
}

resource "aws_db_instance" "postgres" {
  identifier     = "${var.project}-postgres"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage     = 20
  max_allocated_storage = 0 # no autoscaling surprises on the bill
  storage_type          = "gp3"
  storage_encrypted     = true

  username = var.db_username
  password = random_password.db_master.result

  db_subnet_group_name   = aws_db_subnet_group.default.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period = 1
  skip_final_snapshot     = true # demo: destroy must be clean and instant
  deletion_protection     = false
  apply_immediately       = true
}
