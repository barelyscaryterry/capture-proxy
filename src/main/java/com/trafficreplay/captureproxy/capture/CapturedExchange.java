package com.trafficreplay.captureproxy.capture;

import java.util.Map;

/**
 * One proxied request/response pair, recorded after the upstream responds (or fails).
 *
 * @param status          upstream status, or the proxy's own error status (502/504) when forwarding failed
 * @param responseHeaders empty when forwarding failed
 * @param responseBody    empty when forwarding failed
 * @param error           null on success, otherwise a short description of the forwarding failure
 */
public record CapturedExchange(
        String captureId,
        long startedAtEpochMillis,
        String clientIp,
        String method,
        String uri,
        Map<String, String> requestHeaders,
        byte[] requestBody,
        int status,
        Map<String, String> responseHeaders,
        byte[] responseBody,
        long latencyMillis,
        String error) {
}
