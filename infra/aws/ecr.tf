# One repository per image, scanned on push, keeping only the last 10 images (tags are git SHAs).
resource "aws_ecr_repository" "image" {
  for_each = toset(local.images)

  name                 = "${var.project}/${each.key}"
  image_tag_mutability = "MUTABLE" # `latest` is re-pointed on every push
  force_delete         = true      # demo: `terraform destroy` must not be blocked by images

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "keep_last_10" {
  for_each = aws_ecr_repository.image

  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
