package com.trafficreplay.captureproxy;

import com.trafficreplay.captureproxy.ddb.CapturedRequest;
import com.trafficreplay.captureproxy.ddb.DynamoDbAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NettyRequestHandler extends SimpleChannelInboundHandler<HttpObject> {

    private static final Logger log = LoggerFactory.getLogger(NettyRequestHandler.class);

    private final DynamoDbAdapter dynamoDbAdapter;

    private HttpRequest currentRequest;
    private ByteArrayOutputStream currentBody;

    public NettyRequestHandler(DynamoDbAdapter dynamoDbAdapter) {
        this.dynamoDbAdapter = dynamoDbAdapter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        if (msg instanceof HttpRequest request) {
            log.info("Received request: {} {}", request.method(), request.uri());
            currentRequest = request;
            currentBody = new ByteArrayOutputStream();
        }
        if (msg instanceof HttpContent content) {
            try {
                content.content().readBytes(currentBody, content.content().readableBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read request body content", e);
            }
        }
        if (msg instanceof LastHttpContent && currentRequest != null) {
            capture(currentRequest, currentBody.toByteArray());
            currentRequest = null;
            currentBody = null;
        }
    }

    private void capture(HttpRequest request, byte[] body) {
        Map<String, String> headers = new HashMap<>();
        request.headers().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));

        CapturedRequest capturedRequest = new CapturedRequest(
                UUID.randomUUID().toString(),
                request.method().name(),
                request.uri(),
                headers,
                body,
                System.currentTimeMillis());

        dynamoDbAdapter.put(capturedRequest);
    }
}
