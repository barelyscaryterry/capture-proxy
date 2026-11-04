package com.trafficreplay.captureproxy.ddb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;

@Configuration
public class DynamoDbConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(
            @Value("${capture-proxy.ddb.endpoint:}") String endpointOverride) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")));

        if (!endpointOverride.isBlank()) {
            // Local DynamoDB (e.g. dynamodb-local via Docker) doesn't validate credentials,
            // so dummy static credentials avoid needing a real AWS credential chain for local dev.
            builder.endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }

        return builder.build();
    }
}
