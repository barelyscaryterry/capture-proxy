# Capture Proxy

Intercepts and records incoming HTTP traffic. Part of the `traffic-replay-system` project family.

Do not look in or reference the `traffic-replay-common` sibling project when working here — this
project's context should be self-contained.

## Current architecture

- Spring Boot app (`CaptureProxyApplication`) — Tomcat on port 8080 for actuator/health and any
  future REST surface.
- A raw Netty server (`NettyServer`, a `@Component implements SmartLifecycle`) runs alongside it
  on its own port (`capture-proxy.netty.port`, default `8443`), started/stopped with the Spring
  container lifecycle. This is the actual capture path.
- `NettyRequestHandler` sits behind `HttpServerCodec` + `HttpObjectAggregator` and acts as a
  reverse proxy to test-service's beer API via `ForwardingClient`:
  - Only paths in `capture-proxy.forward.allowed-path-prefixes` (default `/api/beers`, matched as an
    exact path or prefix + `/`) are forwarded. Everything else, and any path with `.`/`..` segments,
    gets a local 404. This is the start of the gateway role (auth/rate limits go here later).
  - Strips hop-by-hop headers both ways (`forward/ProxyHeaders`), adds `X-Forwarded-*`, honours
    client keep-alive, always answers HTTP/1.1. Upstream connect failure → 502, connect/read timeout → 504.
  - Each forwarded exchange (request + response + status + latency) goes to a `capture/CaptureSink`
    *after* the upstream responds. Sinks must never block the event loop or throw.
- Capture sinks (`capture-proxy.capture.sink`):
  - `log` (default): `LogFileCaptureSink` writes one JSON line per exchange to the `CAPTURE` logger →
    async appender (`neverBlock`) → rolling file `${capture-proxy.capture.log-dir}/captures.log`
    (`logback-spring.xml`). Credential headers are redacted and bodies are base64, capped at
    `max-body-bytes`. On EC2 the CloudWatch agent ships it to log group
    `/capture-proxy/<env>/captures` (1-day retention, see `infra/terraform/cloudwatch.tf` + user_data).
  - `ddb`: `DynamoDbCaptureSink` writes via the existing `DynamoDbAdapter` on a bounded executor.
    Kept as an alternative while deciding between them.
- `demo-site/`: Vite + React beer UI. In dev, Vite proxies `/api` → `localhost:8443`, so the browser
  goes through Vite, then Netty, then test-service. Serving the built site from the proxy is not done yet.

## Running locally

- Use JDK 17 (`JAVA_HOME=$(/usr/libexec/java_home -v 17)`). Mockito/Byte Buddy in Boot 3.2.0
  can't instrument classes on JDK 23, so `mvn test` fails there.
- test-service (`../test-service`, DynamoDB Local via its docker-compose) on :8080.
- `mvn spring-boot:run -Dspring-boot.run.profiles=local`: Netty on :8443, actuator on :8081 (moved
  so it doesn't clash with test-service), capture log in `./logs/`.
- `cd demo-site && npm run dev` → http://localhost:5173.

## Direction (undecided)

The project is pivoting from load-test traffic replay to capturing user journeys on the demo site
(DOM + mouse position) and replaying them by UUID from the portfolio site. Open questions: a custom
recorder vs rrweb, a login provider (likely Cognito) and anonymous-vs-authenticated access, and the
proxy's long-term role (likely a security/cost gateway). The sections below predate this pivot.
- Netty pinned to `4.1.101.Final` via `io.netty:netty-all` (compile scope), matching the version
  already pulled in transitively by the AWS SDK's `netty-nio-client` — kept aligned deliberately to
  avoid classpath conflicts.

## In-flight change

The original S3-backed capture storage (`s3/S3Adapter` and friends) is being removed and replaced
with a DynamoDB-backed adapter. PR sequencing plan:
1. PR #1: remove S3 logic, add the Netty handler/server scaffolding (current PR).
2. Follow-up commit/PR: add the DDB adapter.
3. Further follow-ups: TLS termination on host, deploy pipeline (see below).

## Planned: TLS and load balancing

Decided direction (not yet implemented):
- **NLB (Layer 4), not ALB** in front of the EC2 instance(s). Reasoning: this proxy needs
  byte-for-byte fidelity of captured HTTP traffic for accurate replay, and an ALB (L7) would
  re-terminate/re-issue the HTTP request on the backend leg, mutating headers/framing. NLB passthrough
  avoids that.
- **TLS terminated on the host**, not at the load balancer. This pairs naturally with NLB (which
  doesn't do TLS itself) — the encrypted bytes arrive untouched and get terminated by an `SslHandler`
  as the first handler in the Netty pipeline, before `HttpServerCodec`. Chosen deliberately even
  though it complicates the pipeline, because the goal is to showcase building this at L7 with full
  control over TLS.
- Consequence: certificate provisioning/rotation becomes this app's problem (e.g. pulling from ACM
  Private CA or Secrets Manager at startup), since ACM can't attach directly to NLB target-facing
  traffic the way it does for ALB listeners.

## Planned: EC2 deployment

- Ships as a Spring Boot fat jar (`mvn clean package`), run via a systemd unit wrapping
  `java -jar capture-proxy-*.jar`.
- Instance auth via IAM instance profile, not embedded credentials — relevant once the DDB adapter
  lands.

## Planned: CI/CD deploy pipeline

Decided direction (not yet implemented):
- **GitHub Actions** on push to `main` builds the jar via `mvn clean package`, bundles it with an
  `appspec.yml` and lifecycle scripts, uploads to an S3 artifact bucket, and triggers
  **AWS CodeDeploy** (`aws deploy create-deployment`). Chosen over raw SSH+scp for native rollback
  support and no long-lived AWS keys in CI (use GitHub OIDC → scoped IAM role instead).
- CodeDeploy agent runs on the EC2 instance(s); `appspec.yml` lifecycle hooks: `ApplicationStop`
  (stop systemd service) → `AfterInstall` (place new jar) → `ApplicationStart` (start systemd
  service) → `ValidateService` (health/test check before the deployment is considered successful).

### Two-environment promotion flow (test → prod)

Planned once integration testing is added:
- **Two CodeDeploy deployment groups** under one CodeDeploy application: `capture-proxy-test` and
  `capture-proxy-prod`, tagged to separate EC2 instances/ASGs.
- Push to `main` deploys to **test only** first. Test's `ValidateService` hook runs the full
  integration test suite against `test-service` as a backend dependency — safe to make strict/heavy
  here since a failure only rolls back the disposable test instance, never prod.
- On **test deployment success**, an **EventBridge rule** (on CodeDeploy's
  `Deployment State-change Notification` event, filtered to `state == SUCCESS` and
  `deploymentGroup == capture-proxy-test`) triggers a **Lambda**.
- The Lambda promotes by calling `create-deployment` again with the **same artifact
  revision** (same S3 bucket/key) targeting `capture-proxy-prod` — a promotion, not a rebuild, so
  what passed integration tests is byte-identical to what ships.
- Prod's own `ValidateService` stays lightweight (health check only) — prod should not depend on
  `test-service` being reachable.
- Open decision, not yet made: whether promotion should be fully automatic on test success, or
  gated behind a manual approval step (e.g. GitHub Actions required reviewers, or a Slack
  approval callback) before the Lambda fires. Worth defaulting to a manual gate early on, until
  the pipeline is trusted.

## Conventions / working preferences

- Keep Netty and any transitively-pulled dependency versions (e.g. from the AWS SDK) explicitly
  aligned in `pom.xml` rather than letting version resolution pick divergent versions silently.
- Prefer Spring-managed lifecycle (`SmartLifecycle` beans) over manual bootstrapping in `main()`
  for anything that needs graceful startup/shutdown alongside the Spring container.
