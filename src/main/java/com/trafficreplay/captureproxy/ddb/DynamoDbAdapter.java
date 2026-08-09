package com.trafficreplay.captureproxy.ddb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DynamoDbAdapter {

    private static final String ATTR_REQUEST_ID = "requestId";
    private static final String ATTR_METHOD = "method";
    private static final String ATTR_URI = "uri";
    private static final String ATTR_HEADERS = "headers";
    private static final String ATTR_BODY = "body";
    private static final String ATTR_CAPTURED_AT = "capturedAt";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbAdapter(DynamoDbClient dynamoDbClient,
                            @Value("${capture-proxy.ddb.table}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    /**
     * Persists a captured request, keyed by its requestId.
     */
    public void put(CapturedRequest capturedRequest) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_REQUEST_ID, AttributeValue.fromS(capturedRequest.requestId()));
        item.put(ATTR_METHOD, AttributeValue.fromS(capturedRequest.method()));
        item.put(ATTR_URI, AttributeValue.fromS(capturedRequest.uri()));
        item.put(ATTR_HEADERS, AttributeValue.fromM(toAttributeMap(capturedRequest.headers())));
        item.put(ATTR_BODY, AttributeValue.fromB(SdkBytes.fromByteArray(capturedRequest.body())));
        item.put(ATTR_CAPTURED_AT, AttributeValue.fromN(Long.toString(capturedRequest.capturedAtEpochMillis())));

        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build();
        try {
            dynamoDbClient.putItem(request);
        } catch (DynamoDbException e) {
            throw new DynamoDbAdapterException(
                    "Failed to put captured request with requestId: " + capturedRequest.requestId(), e);
        }
    }

    /**
     * Fetches a previously captured request by requestId.
     */
    public CapturedRequest get(String requestId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(ATTR_REQUEST_ID, AttributeValue.fromS(requestId)))
                .build();

        GetItemResponse response;
        try {
            response = dynamoDbClient.getItem(request);
        } catch (DynamoDbException e) {
            throw new DynamoDbAdapterException("Failed to get captured request with requestId: " + requestId, e);
        }

        if (!response.hasItem() || response.item().isEmpty()) {
            throw new CapturedRequestNotFoundException("No captured request found for requestId: " + requestId);
        }

        Map<String, AttributeValue> item = response.item();
        return new CapturedRequest(
                item.get(ATTR_REQUEST_ID).s(),
                item.get(ATTR_METHOD).s(),
                item.get(ATTR_URI).s(),
                fromAttributeMap(item.get(ATTR_HEADERS).m()),
                item.get(ATTR_BODY).b().asByteArray(),
                Long.parseLong(item.get(ATTR_CAPTURED_AT).n()));
    }

    private static Map<String, AttributeValue> toAttributeMap(Map<String, String> headers) {
        return headers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> AttributeValue.fromS(e.getValue())));
    }

    private static Map<String, String> fromAttributeMap(Map<String, AttributeValue> attributes) {
        return attributes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().s()));
    }
}
