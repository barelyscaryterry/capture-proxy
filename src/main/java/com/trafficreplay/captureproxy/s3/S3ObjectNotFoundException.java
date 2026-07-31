package com.trafficreplay.captureproxy.s3;

public class S3ObjectNotFoundException extends RuntimeException {

    public S3ObjectNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
