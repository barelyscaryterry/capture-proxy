package com.trafficreplay.captureproxy;

import com.trafficreplay.captureproxy.capture.CaptureSink;
import com.trafficreplay.captureproxy.capture.CapturedExchange;
import com.trafficreplay.captureproxy.forward.ForwardingClient;
import com.trafficreplay.captureproxy.forward.ProxyHeaders;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Reverse-proxy handler: only allowlisted paths are forwarded upstream; everything else is answered locally.
 * Each forwarded exchange is recorded to the {@link CaptureSink} once the upstream responds (or fails).
 */
public class NettyRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(NettyRequestHandler.class);

    private final CaptureSink captureSink;
    private final ForwardingClient forwardingClient;
    private final List<String> allowedPathPrefixes;

    public NettyRequestHandler(CaptureSink captureSink,
                               ForwardingClient forwardingClient,
                               List<String> allowedPathPrefixes) {
        this.captureSink = captureSink;
        this.forwardingClient = forwardingClient;
        this.allowedPathPrefixes = allowedPathPrefixes;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);

        if (request.decoderResult().isFailure()) {
            writeLocalResponse(ctx, HttpResponseStatus.BAD_REQUEST, false);
            return;
        }
        if (!isAllowed(new QueryStringDecoder(request.uri()).path())) {
            log.info("Rejected {} {} (not allowlisted)", request.method(), request.uri());
            writeLocalResponse(ctx, HttpResponseStatus.NOT_FOUND, keepAlive);
            return;
        }

        // Snapshot everything needed for the capture now: the request is released when this method returns.
        String captureId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        String clientIp = clientIp(ctx.channel().remoteAddress());
        String method = request.method().name();
        String uri = request.uri();
        Map<String, String> requestHeaders = toMap(request.headers());
        byte[] requestBody = toBytes(request.content());

        log.info("Forwarding {} {} [{}]", method, uri, captureId);
        ProxyHeaders.stripHopByHop(request.headers());
        ProxyHeaders.addForwarded(request.headers(), clientIp, "http");

        forwardingClient.forward(request).whenCompleteAsync((response, error) -> {
            long latencyMillis = (System.nanoTime() - startNanos) / 1_000_000;
            if (error != null) {
                Throwable cause = unwrap(error);
                HttpResponseStatus status = isTimeout(cause)
                        ? HttpResponseStatus.GATEWAY_TIMEOUT
                        : HttpResponseStatus.BAD_GATEWAY;
                log.warn("Upstream failed for {} {} [{}]: {}", method, uri, captureId, cause.toString());
                captureSink.record(new CapturedExchange(captureId, startedAt, clientIp, method, uri,
                        requestHeaders, requestBody, status.code(), Map.of(), new byte[0],
                        latencyMillis, cause.toString()));
                writeLocalResponse(ctx, status, keepAlive);
                return;
            }

            // The proxy speaks HTTP/1.1 to the client regardless of what the upstream used.
            response.setProtocolVersion(HttpVersion.HTTP_1_1);
            ProxyHeaders.stripHopByHop(response.headers());
            HttpUtil.setContentLength(response, response.content().readableBytes());
            captureSink.record(new CapturedExchange(captureId, startedAt, clientIp, method, uri,
                    requestHeaders, requestBody, response.status().code(), toMap(response.headers()),
                    toBytes(response.content()), latencyMillis, null));
            write(ctx, response, keepAlive);
        }, ctx.executor());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Closing client connection after error", cause);
        ctx.close();
    }

    boolean isAllowed(String path) {
        // Reject dot segments outright rather than trusting the upstream to normalize them
        // (e.g. /api/beers/../../actuator would otherwise pass a naive prefix check).
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return false;
            }
        }
        return allowedPathPrefixes.stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private static void writeLocalResponse(ChannelHandlerContext ctx, HttpResponseStatus status, boolean keepAlive) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(status.toString(), StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN + "; charset=UTF-8");
        HttpUtil.setContentLength(response, response.content().readableBytes());
        write(ctx, response, keepAlive);
    }

    private static void write(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        HttpUtil.setKeepAlive(response, keepAlive);
        ctx.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                log.warn("Failed to write response back to client", future.cause());
                future.channel().close();
            } else if (!keepAlive) {
                future.channel().close();
            }
        });
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static boolean isTimeout(Throwable cause) {
        return cause instanceof ConnectTimeoutException || cause instanceof ReadTimeoutException;
    }

    private static String clientIp(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return String.valueOf(address);
    }

    private static Map<String, String> toMap(HttpHeaders headers) {
        Map<String, String> map = new LinkedHashMap<>();
        headers.forEach(entry -> map.merge(entry.getKey(), entry.getValue(), (a, b) -> a + ", " + b));
        return map;
    }

    private static byte[] toBytes(ByteBuf content) {
        byte[] bytes = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), bytes);
        return bytes;
    }
}
