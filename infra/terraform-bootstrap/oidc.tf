# The GitHub OIDC identity provider is a per-AWS-account singleton, already
# created by test-service's bootstrap (see traffic-replay-system/test-service
# infra/terraform-bootstrap/oidc.tf) in this same account. Referenced here as
# a data source rather than created again - only IAM roles/policies below are
# owned by this config.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "github_actions_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Allow PR runs (plan) and jobs targeting the "production" environment
    # (apply) - matches "plan on PR, apply on merge to main". GitHub's sub
    # claim appends internal numeric IDs to BOTH the owner and repo name
    # (e.g. "repo:owner@123/repo@456:..."), so wildcards are needed after
    # each segment. A job with `environment: production` gets a sub of
    # "...:environment:production" rather than "...:ref:refs/heads/main",
    # regardless of triggering event - see the same note in test-service's
    # oidc.tf, which this mirrors.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${split("/", var.github_repo)[0]}*/${split("/", var.github_repo)[1]}*:pull_request",
        "repo:${split("/", var.github_repo)[0]}*/${split("/", var.github_repo)[1]}*:environment:production",
      ]
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name               = "${var.service_name}-github-actions-terraform"
  assume_role_policy = data.aws_iam_policy_document.github_actions_trust.json
}

# Scoped to exactly what the main config's apply needs to manage: the
# resources it creates, plus the state bucket/lock table this bootstrap
# config created. Broad within those services, since Terraform needs to
# read/create/update/delete whatever it's told to manage - but not admin.
data "aws_iam_policy_document" "github_actions_permissions" {
  statement {
    sid = "TerraformState"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:ListBucket",
    ]
    resources = [
      aws_s3_bucket.tfstate.arn,
      "${aws_s3_bucket.tfstate.arn}/*",
    ]
  }

  statement {
    sid = "TerraformLock"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:DeleteItem",
    ]
    resources = [aws_dynamodb_table.tflock.arn]
  }

  statement {
    sid = "ManagedInfra"
    actions = [
      "ec2:*",
      "dynamodb:*",
      "iam:GetRole",
      "iam:GetPolicy",
      "iam:GetPolicyVersion",
      "iam:GetInstanceProfile",
      "iam:CreateRole",
      "iam:DeleteRole",
      "iam:CreatePolicy",
      "iam:DeletePolicy",
      "iam:CreatePolicyVersion",
      "iam:DeletePolicyVersion",
      "iam:AttachRolePolicy",
      "iam:DetachRolePolicy",
      "iam:CreateInstanceProfile",
      "iam:DeleteInstanceProfile",
      "iam:AddRoleToInstanceProfile",
      "iam:RemoveRoleFromInstanceProfile",
      "iam:TagRole",
      "iam:TagPolicy",
      "iam:TagInstanceProfile",
      "iam:ListRolePolicies",
      "iam:ListAttachedRolePolicies",
      "iam:ListInstanceProfilesForRole",
      "iam:PassRole",
    ]
    resources = ["*"]
  }

  statement {
    sid = "CodeDeployManagement"
    actions = [
      "codedeploy:CreateApplication",
      "codedeploy:DeleteApplication",
      "codedeploy:GetApplication",
      "codedeploy:UpdateApplication",
      "codedeploy:CreateDeploymentGroup",
      "codedeploy:DeleteDeploymentGroup",
      "codedeploy:GetDeploymentGroup",
      "codedeploy:UpdateDeploymentGroup",
      "codedeploy:ListTagsForResource",
      "codedeploy:TagResource",
      "codedeploy:UntagResource",
    ]
    resources = ["*"]
  }

  # The deploy-artifacts bucket doesn't exist yet on first apply, so this
  # can't be scoped to its ARN the way TerraformState above is.
  statement {
    sid = "DeployArtifactsBucketManagement"
    actions = [
      "s3:CreateBucket",
      "s3:DeleteBucket",
      "s3:PutBucketAcl",
      "s3:GetBucketAcl",
      "s3:PutBucketVersioning",
      "s3:GetBucketVersioning",
      "s3:PutBucketPolicy",
      "s3:GetBucketPolicy",
      "s3:PutEncryptionConfiguration",
      "s3:GetEncryptionConfiguration",
      "s3:PutBucketPublicAccessBlock",
      "s3:GetBucketPublicAccessBlock",
      "s3:PutLifecycleConfiguration",
      "s3:GetLifecycleConfiguration",
      "s3:PutBucketTagging",
      "s3:GetBucketTagging",
      "s3:PutBucketOwnershipControls",
      "s3:GetBucketOwnershipControls",
      "s3:PutBucketRequestPayment",
      "s3:GetBucketRequestPayment",
      "s3:PutBucketLogging",
      "s3:GetBucketLogging",
      "s3:GetBucketObjectLockConfiguration",
      "s3:GetAccelerateConfiguration",
      "s3:GetBucketCORS",
      "s3:GetBucketWebsite",
      "s3:GetReplicationConfiguration",
      "s3:GetBucketNotification",
      "s3:ListBucket",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "github_actions_permissions" {
  name   = "${var.service_name}-github-actions-terraform"
  policy = data.aws_iam_policy_document.github_actions_permissions.json
}

resource "aws_iam_role_policy_attachment" "github_actions_permissions" {
  role       = aws_iam_role.github_actions.name
  policy_arn = aws_iam_policy.github_actions_permissions.arn
}

# --- Narrower role for the app-deploy pipeline (deploy.yml) ---
#
# Deliberately separate from the Terraform-apply role above: deploy.yml
# runs on every push to main (far more often, with less review friction,
# than infra changes), so it gets only what it actually needs - upload one
# bundle, trigger one deployment - not the broad ec2:*/dynamodb:*/iam:*
# the Terraform role holds.
data "aws_iam_policy_document" "github_actions_deploy_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # deploy.yml also declares `environment: production`, so it gets the
    # same "environment:production" sub form as the terraform apply job -
    # see the note on github_actions_trust above.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${split("/", var.github_repo)[0]}*/${split("/", var.github_repo)[1]}*:environment:production",
      ]
    }
  }
}

resource "aws_iam_role" "github_actions_deploy" {
  name               = "${var.service_name}-github-actions-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_actions_deploy_trust.json
}

# Bucket name is hardcoded (matches infra/terraform's
# "${local.name_prefix}-deploy-artifacts-966676241497" naming) since it's
# created by the main config, not this bootstrap config - same coupling
# already accepted for the role ARN hardcoded in terraform.yml/deploy.yml.
data "aws_iam_policy_document" "github_actions_deploy_permissions" {
  statement {
    sid       = "UploadDeployArtifacts"
    actions   = ["s3:PutObject"]
    resources = ["arn:aws:s3:::${var.service_name}-test-deploy-artifacts-966676241497/*"]
  }

  statement {
    sid = "TriggerCodeDeploy"
    actions = [
      "codedeploy:CreateDeployment",
      "codedeploy:GetDeployment",
      "codedeploy:GetDeploymentConfig",
      "codedeploy:GetApplicationRevision",
      "codedeploy:RegisterApplicationRevision",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "github_actions_deploy_permissions" {
  name   = "${var.service_name}-github-actions-deploy"
  policy = data.aws_iam_policy_document.github_actions_deploy_permissions.json
}

resource "aws_iam_role_policy_attachment" "github_actions_deploy_permissions" {
  role       = aws_iam_role.github_actions_deploy.name
  policy_arn = aws_iam_policy.github_actions_deploy_permissions.arn
}
