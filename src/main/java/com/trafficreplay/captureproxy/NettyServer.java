package com.trafficreplay.captureproxy;

import com.trafficreplay.captureproxy.capture.CaptureSink;
import com.trafficreplay.captureproxy.forward.ForwardingClient;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NettyServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private static final int MAX_REQUEST_BYTES = 10 * 1024 * 1024;

    private final int port;
    private final CaptureSink captureSink;
    private final ForwardingClient forwardingClient;
    private final List<String> allowedPathPrefixes;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    public NettyServer(@Value("${capture-proxy.netty.port:8443}") int port,
                        @Value("${capture-proxy.forward.allowed-path-prefixes:/api/beers}") List<String> allowedPathPrefixes,
                        CaptureSink captureSink,
                        ForwardingClient forwardingClient) {
        this.port = port;
        this.allowedPathPrefixes = List.copyOf(allowedPathPrefixes);
        this.captureSink = captureSink;
        this.forwardingClient = forwardingClient;
    }

    @Override
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES));
                            ch.pipeline().addLast(new NettyRequestHandler(captureSink, forwardingClient, allowedPathPrefixes));
                        }
                    });

            serverChannel = bootstrap.bind(port).sync().channel();
            running = true;
            log.info("Netty capture server listening on port {}, forwarding paths {}", port, allowedPathPrefixes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting Netty server", e);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("Netty capture server stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
