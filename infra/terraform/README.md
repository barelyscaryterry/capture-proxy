# capture-proxy infra (Terraform)

Self-contained AWS infra for `capture-proxy`: VPC, public subnets, IGW, a
DynamoDB Gateway Endpoint, the `capture-proxy-requests` DynamoDB table, an
EC2 instance running the app, a CodeDeploy application/deployment group that
ships new jars to it, and a CloudWatch dashboard for the app's metrics.

This is intentionally separate from `test-service`'s infra - no shared VPC,
no shared state - so each backend service can own its infra independently.
The one exception is the GitHub OIDC identity provider, which is a
per-AWS-account singleton created once by test-service's bootstrap and
referenced here via a data source (see `infra/terraform-bootstrap/oidc.tf`).

## How changes get applied

State is remote (S3 + DynamoDB lock table, provisioned once by
`infra/terraform-bootstrap` - see that directory's README/comments). Applies
run in CI, not by hand:

- **Pull requests** targeting `main` that touch `infra/terraform/**` get a
  `terraform plan` posted as a PR comment (`.github/workflows/terraform.yml`).
- **Merges to `main`** run `terraform apply` automatically, gated behind the
  `production` GitHub Environment (requires reviewer approval - configure
  reviewers in repo settings).

CI assumes `capture-proxy-github-actions-terraform` via GitHub OIDC - no
long-lived AWS credentials are stored in the repo. That role's permissions
live in `infra/terraform-bootstrap/oidc.tf`; if you add a resource type this
config doesn't already manage, the apply will fail with `AccessDenied` until
that role is granted the matching permissions there.

To run `plan`/`apply` locally (e.g. to preview a change before opening a
PR), you need credentials with equivalent permissions:

```bash
cd infra/terraform
terraform init
terraform plan
```

Don't run `terraform apply` locally against shared state unless you have a
specific reason to bypass the PR/CI flow - it skips the plan-review step and
can race a concurrent CI apply.

## How a code change gets deployed

Separately from Terraform: `.github/workflows/deploy.yml` builds the jar,
zips it with `appspec.yml` and `scripts/`, uploads it to the
`deploy-artifacts` S3 bucket, and triggers a CodeDeploy in-place deployment
to the EC2 instance. It runs whenever `Java CI with Maven` succeeds on
`main` - see the `hooks` in `appspec.yml` for what runs on the instance
during a deploy (`stop_service.sh` / `start_service.sh` /
`validate_service.sh`).

## Connecting to the instance

No SSH keypair and no inbound port 22 - access is via AWS Systems Manager
Session Manager instead:

```bash
aws ssm start-session --target "$(terraform output -raw ec2_instance_id)"
```

## Observability

The app publishes metrics to CloudWatch under the `capture-proxy` namespace
(via Micrometer - see `management.cloudwatch.metrics.export.*` in
`application.properties`), and `cloudwatch.tf` provisions a dashboard
(`terraform output cloudwatch_dashboard_url`) covering DynamoDB and EC2
host metrics, plus HTTP request rate/latency for the Tomcat/actuator port
only - the Netty capture path (`:8443`) isn't Spring MVC-backed, so it has
no request-rate/latency metrics yet.

## Known follow-ups (not implemented in this pass)

- **test-service connectivity**: `app_ingress_cidrs` (in `security_groups.tf`)
  is VPC-internal only for both `:8080` and `:8443`. Once VPC peering with
  test-service exists (or the two share a VPC), narrow this to test-service's
  CIDR/SG specifically rather than the whole capture-proxy VPC - see the
  `TODO(test-service-peering)` in `vpc.tf` and `security_groups.tf`.
- **NLB + host-terminated TLS**: the planned architecture (see the project's
  top-level `CLAUDE.md`) puts an NLB in front of the EC2 instance(s) and
  terminates TLS on the host via an `SslHandler` ahead of `HttpServerCodec`
  in the Netty pipeline. Not implemented here - `netty_port` ingress today
  is plain-HTTP, VPC-internal only.
- **Two-environment (test/prod) promotion flow**: this config provisions a
  single `test` environment. The promotion-on-success flow described in
  `CLAUDE.md` (EventBridge + Lambda promoting the same artifact to a
  `capture-proxy-prod` CodeDeploy deployment group) isn't built yet.
