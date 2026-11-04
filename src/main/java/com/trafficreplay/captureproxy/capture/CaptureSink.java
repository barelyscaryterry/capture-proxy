package com.trafficreplay.captureproxy.capture;

/**
 * Destination for captured exchanges. Implementations are called from the Netty event loop, so they
 * must not block and must never throw - a capture failure must not affect the proxied request.
 */
public interface CaptureSink {

    void record(CapturedExchange exchange);
}
