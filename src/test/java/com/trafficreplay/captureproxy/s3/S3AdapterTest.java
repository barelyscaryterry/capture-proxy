package com.trafficreplay.captureproxy.s3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3AdapterTest {

    private static final String BUCKET = "capture-proxy-bucket";

    private S3Client s3Client;
    private S3Adapter s3Adapter;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        s3Adapter = new S3Adapter(s3Client, BUCKET);
    }

    @Test
    void stowPutsObjectWithBucketKeyAndContentType() {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        s3Adapter.stow("requests/123", content, "application/json");

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

        PutObjectRequest captured = requestCaptor.getValue();
        assertThat(captured.bucket()).isEqualTo(BUCKET);
        assertThat(captured.key()).isEqualTo("requests/123");
        assertThat(captured.contentType()).isEqualTo("application/json");
    }

    @Test
    void stowWrapsS3ExceptionInS3AdapterException() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("boom").build());

        assertThatThrownBy(() -> s3Adapter.stow("requests/123", "data".getBytes(StandardCharsets.UTF_8), "text/plain"))
                .isInstanceOf(S3AdapterException.class)
                .hasMessageContaining("requests/123")
                .hasCauseInstanceOf(S3Exception.class);
    }

    @Test
    void fetchReturnsStreamFromMatchingBucketAndKey() throws IOException {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStreamOf(content));

        try (var result = s3Adapter.fetch("requests/123")) {
            assertThat(result.readAllBytes()).isEqualTo(content);
        }

        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requestCaptor.getValue().key()).isEqualTo("requests/123");
    }

    @Test
    void fetchThrowsS3ObjectNotFoundExceptionWhenKeyMissing() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        assertThatThrownBy(() -> s3Adapter.fetch("requests/missing"))
                .isInstanceOf(S3ObjectNotFoundException.class)
                .hasMessageContaining("requests/missing")
                .hasCauseInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void fetchWrapsOtherS3ExceptionsInS3AdapterException() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("boom").build());

        assertThatThrownBy(() -> s3Adapter.fetch("requests/123"))
                .isInstanceOf(S3AdapterException.class)
                .hasCauseInstanceOf(S3Exception.class);
    }

    @Test
    void fetchBytesReturnsFullyMaterializedContent() {
        byte[] content = "fetch me fully".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStreamOf(content));

        byte[] result = s3Adapter.fetchBytes("requests/123");

        assertThat(result).isEqualTo(content);
    }

    private static ResponseInputStream<GetObjectResponse> responseStreamOf(byte[] content) {
        GetObjectResponse response = GetObjectResponse.builder().build();
        AbortableInputStream abortableInputStream =
                AbortableInputStream.create(new ByteArrayInputStream(content));
        return new ResponseInputStream<>(response, abortableInputStream);
    }
}
