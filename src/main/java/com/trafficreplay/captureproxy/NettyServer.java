package com.trafficreplay.captureproxy;

import com.trafficreplay.captureproxy.ddb.DynamoDbAdapter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class NettyServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private final int port;
    private final DynamoDbAdapter dynamoDbAdapter;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    public NettyServer(@Value("${capture-proxy.netty.port:8443}") int port, DynamoDbAdapter dynamoDbAdapter) {
        this.port = port;
        this.dynamoDbAdapter = dynamoDbAdapter;
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
                            ch.pipeline().addLast(new NettyRequestHandler(dynamoDbAdapter));
                        }
                    });

            serverChannel = bootstrap.bind(port).sync().channel();
            running = true;
            log.info("Netty capture server listening on port {}", port);
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
