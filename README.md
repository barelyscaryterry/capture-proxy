# Capture Proxy

Intercepts and records incoming HTTP traffic. Part of the `traffic-replay-system` project family.

## Status

Scaffold only — `CaptureProxyApplication` is a bare `@SpringBootApplication` with no
controllers, filters, or capture logic yet. There is also no `src/main/resources`
directory, so the app boots with Spring Boot defaults (port 8080, no config file).

## Dependencies

- Java 17
- Maven
- [`traffic-replay-common`](../traffic-replay-common) (sibling project, `com.trafficreplay:traffic-replay-common:1.0.0-SNAPSHOT`)
  — must be built and installed to the local `.m2` repo before this project will build.
- Kafka (via `spring-kafka`) — not required to boot the app, but needed for any actual
  Kafka usage once capture logic is added. No broker address is configured yet, so it
  will default to `localhost:9092`.

## Running locally

```bash
# 1. Install the shared library first (only needed after it changes)
cd ../traffic-replay-common
mvn install -DskipTests

# 2. Run capture-proxy
cd ../capture-proxy
mvn spring-boot:run
```

The app will start on `http://localhost:8080`. With `spring-boot-starter-actuator`
on the classpath, `/actuator/health` is available out of the box.

## Building a jar

```bash
mvn clean package
java -jar target/capture-proxy-1.0.0-SNAPSHOT.jar
```
