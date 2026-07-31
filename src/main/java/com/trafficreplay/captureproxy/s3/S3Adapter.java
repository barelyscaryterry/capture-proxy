package com.trafficreplay.captureproxy.s3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;

@Component
public class S3Adapter {

    private final S3Client s3Client;
    private final String bucketName;

    public S3Adapter(S3Client s3Client, @Value("${capture-proxy.s3.bucket}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    /**
     * Stows a captured request body under the given key.
     */
    public void stow(String key, byte[] content, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            throw new S3AdapterException("Failed to stow object with key: " + key, e);
        }
    }

    /**
     * Fetches a previously stowed request body by key.
     * Caller is responsible for closing the returned stream.
     */
    public InputStream fetch(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        try {
            return s3Client.getObject(request);
        } catch (NoSuchKeyException e) {
            throw new S3ObjectNotFoundException("No object found for key: " + key, e);
        } catch (S3Exception e) {
            throw new S3AdapterException("Failed to fetch object with key: " + key, e);
        }
    }

    /**
     * Fetches a previously stowed object fully materialized as bytes.
     */
    public byte[] fetchBytes(String key) {
        try (InputStream in = fetch(key)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new S3AdapterException("Failed to read object bytes for key: " + key, e);
        }
    }
}
