# Capture Proxy

Intercepts and records incoming HTTP traffic. Part of the `traffic-replay-system` project family.

## Status

- `CaptureProxyApplication` is a Spring Boot app serving actuator/health (and any future REST
  surface) on Tomcat, port 8080.
- A raw Netty server (`NettyServer`) runs alongside it on its own port
  (`capture-proxy.netty.port`, default `8443`) — this is the actual capture path. It's a
  Spring-managed `SmartLifecycle` bean, started/stopped with the Spring container.
- Inbound HTTP requests are parsed by `NettyRequestHandler` (method, URI, headers, body) and
  persisted to DynamoDB via `DynamoDbAdapter`, keyed by a generated `requestId`.
- No response is written back to the client yet — this is a capture pass-through, not a
  functioning proxy.

## Dependencies

- Java 17
- Maven
- AWS credentials with DynamoDB access (`AWS_REGION` env var, default `us-east-1`) — via IAM
  instance profile once deployed, or your usual local AWS credential chain for local runs.
- A DynamoDB table matching `capture-proxy.ddb.table` (default `capture-proxy-requests`) with a
  string partition key `requestId`.
- Kafka (via `spring-kafka`) — not required to boot the app, but needed for any actual
  Kafka usage once that logic is added. No broker address is configured yet, so it will default
  to `localhost:9092`.

## Running locally

```bash
mvn spring-boot:run
```

The Spring app starts on `http://localhost:8080` (`/actuator/health` available via
`spring-boot-starter-actuator`); the Netty capture server starts on `http://localhost:8443`.

## Building a jar

```bash
mvn clean package
java -jar target/capture-proxy-1.0.0-SNAPSHOT.jar
```
