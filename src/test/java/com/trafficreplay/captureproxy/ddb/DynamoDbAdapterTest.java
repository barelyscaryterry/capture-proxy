package com.trafficreplay.captureproxy.ddb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoDbAdapterTest {

    private static final String TABLE = "capture-proxy-requests";

    private DynamoDbClient dynamoDbClient;
    private DynamoDbAdapter dynamoDbAdapter;

    @BeforeEach
    void setUp() {
        dynamoDbClient = mock(DynamoDbClient.class);
        dynamoDbAdapter = new DynamoDbAdapter(dynamoDbClient, TABLE);
    }

    @Test
    void putWritesItemWithTableAndAllAttributes() {
        byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
        CapturedRequest capturedRequest = new CapturedRequest(
                "req-123", "POST", "/checkout", Map.of("Content-Type", "application/json"), body, 1_700_000_000_000L);
        when(dynamoDbClient.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        dynamoDbAdapter.put(capturedRequest);

        ArgumentCaptor<PutItemRequest> requestCaptor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDbClient).putItem(requestCaptor.capture());

        PutItemRequest captured = requestCaptor.getValue();
        assertThat(captured.tableName()).isEqualTo(TABLE);
        Map<String, AttributeValue> item = captured.item();
        assertThat(item.get("requestId").s()).isEqualTo("req-123");
        assertThat(item.get("method").s()).isEqualTo("POST");
        assertThat(item.get("uri").s()).isEqualTo("/checkout");
        assertThat(item.get("headers").m()).containsEntry("Content-Type", AttributeValue.fromS("application/json"));
        assertThat(item.get("body").b().asByteArray()).isEqualTo(body);
        assertThat(item.get("capturedAt").n()).isEqualTo("1700000000000");
    }

    @Test
    void putWrapsDynamoDbExceptionInDynamoDbAdapterException() {
        CapturedRequest capturedRequest = new CapturedRequest(
                "req-123", "GET", "/health", Map.of(), new byte[0], 1_700_000_000_000L);
        when(dynamoDbClient.putItem(any(PutItemRequest.class))).thenThrow(DynamoDbException.builder().message("boom").build());

        assertThatThrownBy(() -> dynamoDbAdapter.put(capturedRequest))
                .isInstanceOf(DynamoDbAdapterException.class)
                .hasMessageContaining("req-123")
                .hasCauseInstanceOf(DynamoDbException.class);
    }

    @Test
    void getReturnsCapturedRequestFromMatchingTableAndKey() {
        byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(responseOf(body));

        CapturedRequest result = dynamoDbAdapter.get("req-123");

        assertThat(result.requestId()).isEqualTo("req-123");
        assertThat(result.method()).isEqualTo("POST");
        assertThat(result.uri()).isEqualTo("/checkout");
        assertThat(result.headers()).containsEntry("Content-Type", "application/json");
        assertThat(result.body()).isEqualTo(body);
        assertThat(result.capturedAtEpochMillis()).isEqualTo(1_700_000_000_000L);

        ArgumentCaptor<GetItemRequest> requestCaptor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDbClient).getItem(requestCaptor.capture());
        assertThat(requestCaptor.getValue().tableName()).isEqualTo(TABLE);
        assertThat(requestCaptor.getValue().key().get("requestId").s()).isEqualTo("req-123");
    }

    @Test
    void getThrowsCapturedRequestNotFoundExceptionWhenItemMissing() {
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertThatThrownBy(() -> dynamoDbAdapter.get("req-missing"))
                .isInstanceOf(CapturedRequestNotFoundException.class)
                .hasMessageContaining("req-missing");
    }

    @Test
    void getWrapsDynamoDbExceptionInDynamoDbAdapterException() {
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenThrow(DynamoDbException.builder().message("boom").build());

        assertThatThrownBy(() -> dynamoDbAdapter.get("req-123"))
                .isInstanceOf(DynamoDbAdapterException.class)
                .hasCauseInstanceOf(DynamoDbException.class);
    }

    private static GetItemResponse responseOf(byte[] body) {
        Map<String, AttributeValue> item = Map.of(
                "requestId", AttributeValue.fromS("req-123"),
                "method", AttributeValue.fromS("POST"),
                "uri", AttributeValue.fromS("/checkout"),
                "headers", AttributeValue.fromM(Map.of("Content-Type", AttributeValue.fromS("application/json"))),
                "body", AttributeValue.fromB(SdkBytes.fromByteArray(body)),
                "capturedAt", AttributeValue.fromN("1700000000000"));
        return GetItemResponse.builder().item(item).build();
    }
}
