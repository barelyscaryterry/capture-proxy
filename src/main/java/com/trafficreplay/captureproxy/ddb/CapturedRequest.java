package com.trafficreplay.captureproxy.ddb;

import java.util.Map;

/**
 * A single captured HTTP request, as persisted to DynamoDB.
 */
public record CapturedRequest(
        String requestId,
        String method,
        String uri,
        Map<String, String> headers,
        byte[] body,
        long capturedAtEpochMillis) {
}
