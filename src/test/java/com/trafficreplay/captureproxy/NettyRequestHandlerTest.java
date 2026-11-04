package com.trafficreplay.captureproxy;

import com.trafficreplay.captureproxy.capture.CaptureSink;
import com.trafficreplay.captureproxy.capture.CapturedExchange;
import com.trafficreplay.captureproxy.forward.ForwardingClient;
import io.netty.buffer.Unpooled;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NettyRequestHandlerTest {

    private ForwardingClient forwardingClient;
    private CaptureSink captureSink;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        forwardingClient = mock(ForwardingClient.class);
        captureSink = mock(CaptureSink.class);
        channel = new EmbeddedChannel(new NettyRequestHandler(captureSink, forwardingClient, List.of("/api/beers")));
    }

    @Test
    void nonAllowlistedPathGets404WithoutForwardingOrCapture() {
        FullHttpResponse response = send(request(HttpMethod.GET, "/actuator/health"));

        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        verify(forwardingClient, never()).forward(any());
        verify(captureSink, never()).record(any());
        response.release();
    }

    @Test
    void dotSegmentsAreRejected() {
        FullHttpResponse response = send(request(HttpMethod.GET, "/api/beers/%2e%2e/actuator"));

        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        verify(forwardingClient, never()).forward(any());
        response.release();
    }

    @Test
    void prefixMustEndAtASegmentBoundary() {
        FullHttpResponse response = send(request(HttpMethod.GET, "/api/beersecret"));

        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        response.release();
    }

    @Test
    void allowlistedRequestIsForwardedWithProxyHeadersAndCaptured() {
        DefaultFullHttpResponse upstream = new DefaultFullHttpResponse(HttpVersion.HTTP_1_0, HttpResponseStatus.OK,
                Unpooled.copiedBuffer("[]", StandardCharsets.UTF_8));
        upstream.headers().set(HttpHeaderNames.CONNECTION, "close");
        ArgumentCaptor<FullHttpRequest> forwarded = ArgumentCaptor.forClass(FullHttpRequest.class);
        when(forwardingClient.forward(forwarded.capture())).thenAnswer(invocation -> {
            FullHttpRequest req = invocation.getArgument(0);
            // Assert on the request while it is still alive (it's released after channelRead0).
            assertThat(req.headers().get("X-Forwarded-For")).isNotBlank();
            assertThat(req.headers().get("X-Forwarded-Proto")).isEqualTo("http");
            assertThat(req.headers().get("X-Forwarded-Host")).isEqualTo("demo.local");
            assertThat(req.headers().contains("Keep-Alive")).isFalse();
            return CompletableFuture.completedFuture(upstream);
        });

        FullHttpRequest request = request(HttpMethod.GET, "/api/beers?style=IPA");
        request.headers().set(HttpHeaderNames.HOST, "demo.local");
        request.headers().set("Keep-Alive", "timeout=5");
        FullHttpResponse response = send(request);

        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.protocolVersion()).isEqualTo(HttpVersion.HTTP_1_1);
        assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("[]");
        // Upstream's "Connection: close" is hop-by-hop and must not leak to the client's keep-alive connection.
        assertThat(response.headers().contains(HttpHeaderNames.CONNECTION)).isFalse();
        assertThat(channel.isOpen()).isTrue();

        ArgumentCaptor<CapturedExchange> captured = ArgumentCaptor.forClass(CapturedExchange.class);
        verify(captureSink).record(captured.capture());
        assertThat(captured.getValue().status()).isEqualTo(200);
        assertThat(captured.getValue().uri()).isEqualTo("/api/beers?style=IPA");
        assertThat(new String(captured.getValue().responseBody(), StandardCharsets.UTF_8)).isEqualTo("[]");
        assertThat(captured.getValue().error()).isNull();
        response.release();
    }

    @Test
    void upstreamConnectFailureReturns502AndIsCaptured() {
        when(forwardingClient.forward(any()))
                .thenReturn(CompletableFuture.failedFuture(new ConnectException("Connection refused")));

        FullHttpResponse response = send(request(HttpMethod.GET, "/api/beers"));

        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_GATEWAY);
        ArgumentCaptor<CapturedExchange> captured = ArgumentCaptor.forClass(CapturedExchange.class);
        verify(captureSink).record(captured.capture());
        assertThat(captured.getValue().status()).isEqualTo(502);
        assertThat(captured.getValue().error()).contains("Connection refused");
        response.release();
    }

    @Test
    void upstreamTimeoutReturns504() {
        when(forwardingClient.forward(any()))
                .thenReturn(CompletableFuture.failedFuture(new ConnectTimeoutException("timed out")));

        FullHttpResponse response = send(request(HttpMethod.GET, "/api/beers/123"));

        assertThat(response.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        response.release();
    }

    @Test
    void connectionCloseRequestClosesChannelAfterResponse() {
        FullHttpRequest request = request(HttpMethod.GET, "/nope");
        request.headers().set(HttpHeaderNames.CONNECTION, "close");

        FullHttpResponse response = send(request);

        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
        assertThat(channel.isOpen()).isFalse();
        response.release();
    }

    private FullHttpResponse send(FullHttpRequest request) {
        channel.writeInbound(request);
        channel.runPendingTasks();
        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        return response;
    }

    private static FullHttpRequest request(HttpMethod method, String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, Unpooled.EMPTY_BUFFER);
    }
}
