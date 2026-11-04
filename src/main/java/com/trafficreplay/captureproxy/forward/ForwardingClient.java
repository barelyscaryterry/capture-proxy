package com.trafficreplay.captureproxy.forward;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Forwards proxied requests to the upstream service (test-service's beer API) and relays its response back.
 * One upstream connection per request, closed after the response - connection pooling is a later step.
 */
@Component
public class ForwardingClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ForwardingClient.class);
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private EventLoopGroup group;
    private volatile boolean running = false;

    public ForwardingClient(@Value("${capture-proxy.forward.host}") String host,
                             @Value("${capture-proxy.forward.port}") int port,
                             @Value("${capture-proxy.forward.connect-timeout-ms:2000}") int connectTimeoutMillis,
                             @Value("${capture-proxy.forward.read-timeout-ms:10000}") int readTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * Sends a copy of {@code inboundRequest} upstream. The caller keeps ownership of {@code inboundRequest};
     * the returned response is owned by the caller and must be released (writing it to a channel does that).
     * Fails with {@link io.netty.channel.ConnectTimeoutException} or
     * {@link io.netty.handler.timeout.ReadTimeoutException} on timeout, other exceptions otherwise.
     */
    public CompletableFuture<FullHttpResponse> forward(FullHttpRequest inboundRequest) {
        CompletableFuture<FullHttpResponse> responseFuture = new CompletableFuture<>();

        FullHttpRequest outboundRequest = new DefaultFullHttpRequest(
                inboundRequest.protocolVersion(),
                inboundRequest.method(),
                inboundRequest.uri(),
                inboundRequest.content().retainedDuplicate());
        outboundRequest.headers().set(inboundRequest.headers());
        outboundRequest.headers().set(HttpHeaderNames.HOST, host + ":" + port);
        outboundRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, outboundRequest.content().readableBytes());
        // The upstream connection is single-use, so tell the upstream not to hold it open.
        outboundRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS));
                        ch.pipeline().addLast(new HttpClientCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_RESPONSE_BYTES));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                                if (!responseFuture.complete(response.retainedDuplicate())) {
                                    response.release();
                                }
                                ctx.close();
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                // Upstream closed before sending a full response.
                                responseFuture.completeExceptionally(
                                        new IOException("Upstream closed connection before responding"));
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                responseFuture.completeExceptionally(cause);
                                ctx.close();
                            }
                        });
                    }
                })
                .connect(host, port)
                .addListener((ChannelFutureListener) connectFuture -> {
                    if (!connectFuture.isSuccess()) {
                        // Never written, so we still own the retained content.
                        ReferenceCountUtil.release(outboundRequest);
                        responseFuture.completeExceptionally(connectFuture.cause());
                        return;
                    }
                    Channel channel = connectFuture.channel();
                    channel.writeAndFlush(outboundRequest).addListener((ChannelFutureListener) writeFuture -> {
                        if (!writeFuture.isSuccess()) {
                            responseFuture.completeExceptionally(writeFuture.cause());
                            writeFuture.channel().close();
                        }
                    });
                });

        return responseFuture;
    }

    @Override
    public void start() {
        group = new NioEventLoopGroup();
        running = true;
        log.info("Forwarding client targeting {}:{} (connect timeout {}ms, read timeout {}ms)",
                host, port, connectTimeoutMillis, readTimeoutMillis);
    }

    @Override
    public void stop() {
        running = false;
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
