data "aws_iam_policy_document" "ec2_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ec2" {
  name               = "${local.name_prefix}-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json
}

# Scoped to exactly the operations DynamoDbAdapter uses (put/get) on
# exactly the capture-requests table.
data "aws_iam_policy_document" "dynamodb_access" {
  statement {
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
    ]
    resources = [aws_dynamodb_table.capture_requests.arn]
  }
}

resource "aws_iam_policy" "dynamodb_access" {
  name   = "${local.name_prefix}-dynamodb-access"
  policy = data.aws_iam_policy_document.dynamodb_access.json
}

resource "aws_iam_role_policy_attachment" "dynamodb_access" {
  role       = aws_iam_role.ec2.name
  policy_arn = aws_iam_policy.dynamodb_access.arn
}

# cloudwatch:PutMetricData has no resource-level permissions - AWS requires "*".
# Scoped down instead via a condition on the namespace the app actually publishes to.
data "aws_iam_policy_document" "cloudwatch_metrics" {
  statement {
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = [var.cloudwatch_namespace]
    }
  }
}

resource "aws_iam_policy" "cloudwatch_metrics" {
  name   = "${local.name_prefix}-cloudwatch-metrics"
  policy = data.aws_iam_policy_document.cloudwatch_metrics.json
}

resource "aws_iam_role_policy_attachment" "cloudwatch_metrics" {
  role       = aws_iam_role.ec2.name
  policy_arn = aws_iam_policy.cloudwatch_metrics.arn
}

# The CloudWatch agent ships the capture log file into exactly one log group.
# No logs:CreateLogGroup - the group is owned by Terraform (cloudwatch.tf).
data "aws_iam_policy_document" "capture_logs" {
  statement {
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams",
    ]
    resources = ["${aws_cloudwatch_log_group.captures.arn}:*"]
  }
}

resource "aws_iam_policy" "capture_logs" {
  name   = "${local.name_prefix}-capture-logs"
  policy = data.aws_iam_policy_document.capture_logs.json
}

resource "aws_iam_role_policy_attachment" "capture_logs" {
  role       = aws_iam_role.ec2.name
  policy_arn = aws_iam_policy.capture_logs.arn
}

# Enables SSM Session Manager access (no SSH keypair, no inbound port 22).
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# The CodeDeploy agent running on-instance pulls deployment bundles
# directly from S3 - this is all it needs beyond what SSM already grants.
data "aws_iam_policy_document" "codedeploy_agent_access" {
  statement {
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = ["${aws_s3_bucket.deploy_artifacts.arn}/*"]
  }
  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.deploy_artifacts.arn]
  }
}

resource "aws_iam_policy" "codedeploy_agent_access" {
  name   = "${local.name_prefix}-codedeploy-agent-access"
  policy = data.aws_iam_policy_document.codedeploy_agent_access.json
}

resource "aws_iam_role_policy_attachment" "codedeploy_agent_access" {
  role       = aws_iam_role.ec2.name
  policy_arn = aws_iam_policy.codedeploy_agent_access.arn
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${local.name_prefix}-ec2-profile"
  role = aws_iam_role.ec2.name
}
