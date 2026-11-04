package com.trafficreplay.captureproxy.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LogFileCaptureSinkTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> lines = new ArrayList<>();
    private final LogFileCaptureSink sink = new LogFileCaptureSink(4, lines::add);

    @Test
    void writesOneJsonLinePerExchange() throws Exception {
        sink.record(exchange("ab".getBytes(StandardCharsets.UTF_8), 200, null));
        sink.record(exchange(new byte[0], 502, "java.net.ConnectException: refused"));

        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line -> assertThat(line).doesNotContain("\n"));

        JsonNode first = mapper.readTree(lines.get(0));
        assertThat(first.get("captureId").asText()).isEqualTo("id-1");
        assertThat(first.get("method").asText()).isEqualTo("POST");
        assertThat(first.get("uri").asText()).isEqualTo("/api/beers");
        assertThat(first.get("status").asInt()).isEqualTo(200);
        assertThat(first.get("latencyMs").asLong()).isEqualTo(12);
        assertThat(first.get("ts").asText()).isEqualTo("1970-01-01T00:00:01Z");
        assertThat(first.has("error")).isFalse();

        JsonNode second = mapper.readTree(lines.get(1));
        assertThat(second.get("status").asInt()).isEqualTo(502);
        assertThat(second.get("error").asText()).contains("refused");
    }

    @Test
    void bodiesAreBase64EncodedAndCapped() throws Exception {
        sink.record(exchange("abcdefgh".getBytes(StandardCharsets.UTF_8), 200, null));

        JsonNode body = mapper.readTree(lines.get(0)).get("requestBody");
        assertThat(body.get("size").asInt()).isEqualTo(8);
        assertThat(body.get("truncated").asBoolean()).isTrue();
        assertThat(new String(Base64.getDecoder().decode(body.get("base64").asText()), StandardCharsets.UTF_8))
                .isEqualTo("abcd");

        JsonNode responseBody = mapper.readTree(lines.get(0)).get("responseBody");
        assertThat(responseBody.get("truncated").asBoolean()).isFalse();
        assertThat(new String(Base64.getDecoder().decode(responseBody.get("base64").asText()), StandardCharsets.UTF_8))
                .isEqualTo("ok");
    }

    @Test
    void credentialHeadersAreRedacted() throws Exception {
        sink.record(exchange(new byte[0], 200, null));

        JsonNode json = mapper.readTree(lines.get(0));
        assertThat(json.get("requestHeaders").get("Authorization").asText()).isEqualTo("[REDACTED]");
        assertThat(json.get("requestHeaders").get("content-type").asText()).isEqualTo("application/json");
        assertThat(json.get("responseHeaders").get("Set-Cookie").asText()).isEqualTo("[REDACTED]");
    }

    @Test
    void writerFailureDoesNotPropagate() {
        LogFileCaptureSink failing = new LogFileCaptureSink(4, line -> {
            throw new IllegalStateException("disk full");
        });

        assertThatCode(() -> failing.record(exchange(new byte[0], 200, null))).doesNotThrowAnyException();
    }

    private static CapturedExchange exchange(byte[] requestBody, int status, String error) {
        return new CapturedExchange(
                "id-1",
                1_000L,
                "127.0.0.1",
                "POST",
                "/api/beers",
                Map.of("Authorization", "Bearer secret", "content-type", "application/json"),
                requestBody,
                status,
                Map.of("Set-Cookie", "session=abc"),
                "ok".getBytes(StandardCharsets.UTF_8),
                12L,
                error);
    }
}
