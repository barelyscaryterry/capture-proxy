# S3 bucket CI uploads deployment bundles (jar + appspec + scripts) to.
resource "aws_s3_bucket" "deploy_artifacts" {
  bucket = "${local.name_prefix}-deploy-artifacts-966676241497"

  tags = {
    Name = "${local.name_prefix}-deploy-artifacts"
  }
}

resource "aws_s3_bucket_versioning" "deploy_artifacts" {
  bucket = aws_s3_bucket.deploy_artifacts.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "deploy_artifacts" {
  bucket = aws_s3_bucket.deploy_artifacts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "deploy_artifacts" {
  bucket                  = aws_s3_bucket.deploy_artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Each deploy uploads a uniquely-named (per-commit-SHA) object that's never
# overwritten, so bundles accumulate indefinitely without this.
resource "aws_s3_bucket_lifecycle_configuration" "deploy_artifacts" {
  bucket = aws_s3_bucket.deploy_artifacts.id
  rule {
    id     = "expire-old-deploy-artifacts"
    status = "Enabled"
    filter {}
    expiration {
      days = 30
    }
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

# CodeDeploy control-plane service role - NOT instance-level. This is what
# the CodeDeploy service itself assumes to orchestrate deployments (query
# instance status, etc.), distinct from the EC2 instance role in iam.tf.
data "aws_iam_policy_document" "codedeploy_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["codedeploy.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "codedeploy" {
  name               = "${local.name_prefix}-codedeploy-role"
  assume_role_policy = data.aws_iam_policy_document.codedeploy_trust.json
}

resource "aws_iam_role_policy_attachment" "codedeploy" {
  role       = aws_iam_role.codedeploy.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSCodeDeployRole"
}

resource "aws_codedeploy_app" "this" {
  name             = local.name_prefix
  compute_platform = "Server"
}

resource "aws_codedeploy_deployment_group" "this" {
  app_name               = aws_codedeploy_app.this.name
  deployment_group_name  = "${local.name_prefix}-dg"
  service_role_arn        = aws_iam_role.codedeploy.arn
  deployment_config_name = "CodeDeployDefault.AllAtOnce"

  ec2_tag_filter {
    key   = "Name"
    type  = "KEY_AND_VALUE"
    value = "${local.name_prefix}-app"
  }

  deployment_style {
    deployment_type   = "IN_PLACE"
    deployment_option = "WITHOUT_TRAFFIC_CONTROL"
  }

  auto_rollback_configuration {
    enabled = true
    events  = ["DEPLOYMENT_FAILURE"]
  }
}
