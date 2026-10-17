# infra/ — running ShopFlow on AWS (option A: EC2 + compose + RDS)

This folder holds the local observability config (`grafana/`, `prometheus/`, `postgres/`) and the
Terraform for the AWS demo in `aws/`. The AWS layout follows ADR 0010: one EC2 box running
`docker compose`, RDS PostgreSQL, images in ECR tagged by commit SHA, GitHub OIDC for deploys,
secrets in Secrets Manager, a 10 USD budget alert. The later move to ECS Fargate (option B) is the
planned next step and will get its own ADR.

## Prerequisites

- An AWS account and the AWS CLI logged in (`aws sts get-caller-identity` works), Terraform >= 1.6.
- The repo on GitHub (public, or the instance needs a token to clone — adjust `user-data.sh.tftpl`).
- ~20 minutes: RDS takes most of it.

## 1. Create the infrastructure

```bash
cd infra/aws
cat > terraform.tfvars <<EOF
github_repo  = "<owner>/<repo>"
alert_email  = "you@example.com"
allowed_cidr = "0.0.0.0/0"        # or "<your-ip>/32" while you are not demoing
EOF
terraform init
terraform plan
terraform apply
terraform output github_actions_settings
```

State is local (`terraform.tfstate`, keep it out of git); `providers.tf` shows the S3 switch.

## 2. Wire up GitHub Actions

In the repo: Settings -> Secrets and variables -> Actions. Set what `terraform output github_actions_settings` prints:

| Kind     | Name                  | Value                                  |
| -------- | --------------------- | -------------------------------------- |
| variable | `AWS_DEPLOY_ENABLED`  | `true` (unset/false = build only, no AWS) |
| variable | `AWS_REGION`          | e.g. `ap-southeast-1`                  |
| variable | `EC2_INSTANCE_ID`     | `i-...`                                |
| variable | `DEMO_HOST`           | the Elastic IP                         |
| secret   | `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::<account>:role/shopflow-github-deploy` |

## 3. How a deploy happens

1. Push/merge to `main` -> `.github/workflows/ci.yml` builds and tests, then builds the 9 images
   and pushes `<registry>/shopflow/<name>:<sha>` and `:latest` to ECR (OIDC login, no stored keys).
2. CI success triggers `.github/workflows/deploy.yml`, which assumes the deploy role and sends an
   SSM Run Command to the instance: `git checkout <sha>`, `IMAGE_TAG=<sha> docker compose -f
   docker-compose.yml -f docker-compose.prod.yml pull && up -d --no-build`.
3. The workflow then smoke tests `http://<DEMO_HOST>:8080/actuator/health` and `GET /api/v1/products`
   from the runner (retrying ~5 min), and a second SSM command checks `/actuator/health` of all 7
   services inside the box. Any non-`UP` fails the run.

Redeploy a specific commit by hand: Actions -> Deploy -> Run workflow -> `image_tag = <full sha>`.

The first `terraform apply` happens before any image exists, so the instance boots with an empty
stack; the first push to `main` after that brings it up.

## 4. Smoke test by hand

```bash
HOST=$(cd infra/aws && terraform output -raw public_ip)
curl -s http://$HOST:8080/actuator/health          # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' http://$HOST:8080/api/v1/products   # 200
# inside the box (no SSH; SSM Session Manager):
aws ssm start-session --target $(cd infra/aws && terraform output -raw instance_id)
sudo -i; cd /opt/shopflow
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
docker compose -f docker-compose.yml -f docker-compose.prod.yml exec -T order-service wget -qO- http://localhost:8084/actuator/health
```

## 5. Stop and start to save money

```bash
ID=$(cd infra/aws && terraform output -raw instance_id)
aws ec2 stop-instances  --instance-ids $ID    # when you are done demoing
aws ec2 start-instances --instance-ids $ID    # compose restarts the containers (restart: unless-stopped)
aws rds stop-db-instance  --db-instance-identifier shopflow-postgres   # optional; AWS restarts it after 7 days
aws rds start-db-instance --db-instance-identifier shopflow-postgres
```

The Elastic IP stays attached, so `DEMO_HOST` does not change. `terraform destroy` removes everything
(RDS has `skip_final_snapshot`, the secret has no recovery window, ECR repos are force-deleted).

## Rough monthly cost (ap-southeast-1)

| Item                        | Cost                                             |
| --------------------------- | ------------------------------------------------ |
| EC2 t3.large, 24x7          | ~60 USD (~2 USD/day) -> stop the instance when not demoing |
| 30 GB gp3 root volume       | ~3 USD                                           |
| RDS db.t3.micro + 20 GB     | free tier for the first 12 months, then ~15 USD  |
| Elastic IP (while running)  | ~3.6 USD                                         |
| ECR (9 repos x <=10 images) | ~1 USD                                           |
| Data transfer, Secrets Mgr  | < 1 USD                                          |

The 10 USD budget (`budget.tf`) alerts at 80% and 100%; running the box only during demos keeps you
under it. Stop the instance when not demoing.
