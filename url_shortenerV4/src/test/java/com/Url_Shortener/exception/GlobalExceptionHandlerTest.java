package com.Url_Shortener.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void dynamoDbTimeoutIsServiceUnavailable() {
        var response = new GlobalExceptionHandler()
                .handleDynamoDbUnavailable(ApiCallTimeoutException.create(3000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
