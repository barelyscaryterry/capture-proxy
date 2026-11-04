package com.trafficreplay.captureproxy.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Writes each exchange as one JSON line to the dedicated {@code CAPTURE} logger. logback-spring.xml routes
 * that logger through a non-blocking async appender into a rolling file, which the CloudWatch agent ships
 * to the {@code /capture-proxy/captures} log group.
 */
@Component
@ConditionalOnProperty(name = "capture-proxy.capture.sink", havingValue = "log", matchIfMissing = true)
public class LogFileCaptureSink implements CaptureSink {

    static final String CAPTURE_LOGGER_NAME = "CAPTURE";

    private static final Logger log = LoggerFactory.getLogger(LogFileCaptureSink.class);
    private static final Logger captureLog = LoggerFactory.getLogger(CAPTURE_LOGGER_NAME);

    /** Credentials/session headers are never written to logs. */
    private static final Set<String> REDACTED_HEADERS =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");
    private static final String REDACTED = "[REDACTED]";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxBodyBytes;
    private final Consumer<String> writer;

    @Autowired
    public LogFileCaptureSink(@Value("${capture-proxy.capture.max-body-bytes:8192}") int maxBodyBytes) {
        this(maxBodyBytes, captureLog::info);
    }

    LogFileCaptureSink(int maxBodyBytes, Consumer<String> writer) {
        this.maxBodyBytes = maxBodyBytes;
        this.writer = writer;
    }

    @Override
    public void record(CapturedExchange exchange) {
        try {
            writer.accept(objectMapper.writeValueAsString(toJson(exchange)));
        } catch (Exception e) {
            log.warn("Failed to record capture {}", exchange.captureId(), e);
        }
    }

    private Map<String, Object> toJson(CapturedExchange exchange) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ts", Instant.ofEpochMilli(exchange.startedAtEpochMillis()).toString());
        json.put("captureId", exchange.captureId());
        json.put("clientIp", exchange.clientIp());
        json.put("method", exchange.method());
        json.put("uri", exchange.uri());
        json.put("status", exchange.status());
        json.put("latencyMs", exchange.latencyMillis());
        json.put("requestHeaders", redact(exchange.requestHeaders()));
        json.put("requestBody", body(exchange.requestBody()));
        json.put("responseHeaders", redact(exchange.responseHeaders()));
        json.put("responseBody", body(exchange.responseBody()));
        if (exchange.error() != null) {
            json.put("error", exchange.error());
        }
        return json;
    }

    private static Map<String, String> redact(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, value) ->
                result.put(name, REDACTED_HEADERS.contains(name.toLowerCase(Locale.ROOT)) ? REDACTED : value));
        return result;
    }

    private Map<String, Object> body(byte[] bytes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", bytes.length);
        body.put("truncated", bytes.length > maxBodyBytes);
        body.put("base64", Base64.getEncoder().encodeToString(
                bytes.length > maxBodyBytes ? Arrays.copyOf(bytes, maxBodyBytes) : bytes));
        return body;
    }
}
