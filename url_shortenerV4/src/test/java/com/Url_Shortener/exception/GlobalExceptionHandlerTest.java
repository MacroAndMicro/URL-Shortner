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

    @Test
    void openCircuitBreakerIsServiceUnavailable() {
        var refused = io.github.resilience4j.circuitbreaker.CallNotPermittedException.createCallNotPermittedException(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("dynamodb"));

        var response = new GlobalExceptionHandler().handleCircuitOpen(refused);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().getMessage()).contains("temporarily unavailable");
    }
}
