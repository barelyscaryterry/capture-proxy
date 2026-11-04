# capture-proxy Terraform bootstrap

Creates the prerequisites the *main* `infra/terraform/` config needs before
it can run in GitHub Actions:

- An S3 bucket + DynamoDB lock table for remote Terraform state.
- IAM roles that GitHub Actions assumes via OIDC (no long-lived AWS keys
  stored in GitHub). The GitHub OIDC identity provider itself is **not**
  created here - it's a per-AWS-account singleton already created by
  test-service's bootstrap in this same account, and is referenced via a
  data source (see `oidc.tf`).

This has to be applied **once, manually, by a human** - it can't itself run
in the CI pipeline it's setting up (chicken-and-egg), and it uses local
state deliberately, separate from the main config's remote state.

## Usage

```bash
cd infra/terraform-bootstrap
terraform init
terraform plan -out=tfplan
terraform apply tfplan
```

Note the outputs (`state_bucket_name`, `lock_table_name`,
`github_actions_role_arn`, `github_actions_deploy_role_arn`) - the main
config's backend block and the GitHub Actions workflows both need them.

## Note on credentials

Apply this using a scoped IAM identity for infra work, not AWS root
credentials - see test-service's bootstrap README for the same note; that
account's bootstrap was applied with root creds and is due for cleanup.
