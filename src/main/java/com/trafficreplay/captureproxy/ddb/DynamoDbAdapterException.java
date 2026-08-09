package com.trafficreplay.captureproxy.ddb;

public class DynamoDbAdapterException extends RuntimeException {

    public DynamoDbAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
