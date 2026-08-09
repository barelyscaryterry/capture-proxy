package com.trafficreplay.captureproxy.ddb;

public class CapturedRequestNotFoundException extends RuntimeException {

    public CapturedRequestNotFoundException(String message) {
        super(message);
    }
}
