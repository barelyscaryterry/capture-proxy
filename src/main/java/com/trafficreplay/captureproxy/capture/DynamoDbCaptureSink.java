package com.trafficreplay.captureproxy.capture;

import com.trafficreplay.captureproxy.ddb.CapturedRequest;
import com.trafficreplay.captureproxy.ddb.DynamoDbAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Alternative sink (capture-proxy.capture.sink=ddb) that persists the request half of each exchange via the
 * existing {@link DynamoDbAdapter}. DynamoDbClient is blocking, so writes happen on a small bounded executor
 * off the Netty event loop; when the queue is full, captures are dropped rather than applying backpressure.
 */
@Component
@ConditionalOnProperty(name = "capture-proxy.capture.sink", havingValue = "ddb")
public class DynamoDbCaptureSink implements CaptureSink, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbCaptureSink.class);

    private final DynamoDbAdapter dynamoDbAdapter;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1_000));

    public DynamoDbCaptureSink(DynamoDbAdapter dynamoDbAdapter) {
        this.dynamoDbAdapter = dynamoDbAdapter;
    }

    @Override
    public void record(CapturedExchange exchange) {
        CapturedRequest request = new CapturedRequest(
                exchange.captureId(),
                exchange.method(),
                exchange.uri(),
                exchange.requestHeaders(),
                exchange.requestBody(),
                exchange.startedAtEpochMillis());
        try {
            executor.execute(() -> {
                try {
                    dynamoDbAdapter.put(request);
                } catch (Exception e) {
                    log.warn("Failed to write capture {} to DynamoDB", exchange.captureId(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("DynamoDB capture queue full, dropping capture {}", exchange.captureId());
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
