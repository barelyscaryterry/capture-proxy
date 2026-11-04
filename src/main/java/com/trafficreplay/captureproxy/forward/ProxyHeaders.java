package com.trafficreplay.captureproxy.forward;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.List;
import java.util.Locale;

/**
 * Header rules for a reverse proxy: hop-by-hop headers (RFC 9110 §7.6.1) describe a single connection and
 * must not be forwarded, and the upstream is told about the original client via X-Forwarded-*.
 */
public final class ProxyHeaders {

    private static final List<CharSequence> HOP_BY_HOP = List.of(
            HttpHeaderNames.CONNECTION,
            HttpHeaderNames.KEEP_ALIVE,
            HttpHeaderNames.PROXY_AUTHENTICATE,
            HttpHeaderNames.PROXY_AUTHORIZATION,
            HttpHeaderNames.PROXY_CONNECTION,
            HttpHeaderNames.TE,
            HttpHeaderNames.TRAILER,
            HttpHeaderNames.TRANSFER_ENCODING,
            HttpHeaderNames.UPGRADE);

    static final String X_FORWARDED_FOR = "X-Forwarded-For";
    static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    static final String X_FORWARDED_HOST = "X-Forwarded-Host";

    private ProxyHeaders() {
    }

    /** Removes hop-by-hop headers, including any extra ones the sender listed in its Connection header. */
    public static void stripHopByHop(HttpHeaders headers) {
        for (String connection : headers.getAll(HttpHeaderNames.CONNECTION)) {
            for (String token : connection.split(",")) {
                String name = token.trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    headers.remove(name);
                }
            }
        }
        HOP_BY_HOP.forEach(headers::remove);
    }

    /** Appends the client to X-Forwarded-For and records the original scheme and host. */
    public static void addForwarded(HttpHeaders headers, String clientIp, String proto) {
        String existingFor = headers.get(X_FORWARDED_FOR);
        headers.set(X_FORWARDED_FOR, existingFor == null ? clientIp : existingFor + ", " + clientIp);
        headers.set(X_FORWARDED_PROTO, proto);
        String host = headers.get(HttpHeaderNames.HOST);
        if (host != null) {
            headers.set(X_FORWARDED_HOST, host);
        }
    }
}
