package com.Url_Shortener.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreakersTest {

    @Test
    void dynamoDbOutagesAreNetworkAndServerErrorsOnly() {
        assertThat(Breakers.isDynamoDbOutage(SdkClientException.create("timeout"))).isTrue();
        assertThat(Breakers.isDynamoDbOutage(DynamoDbException.builder().statusCode(503).build())).isTrue();
        assertThat(Breakers.isDynamoDbOutage(ConditionalCheckFailedException.builder().statusCode(400).build())).isFalse();
        assertThat(Breakers.isDynamoDbOutage(new IllegalArgumentException())).isFalse();
    }

    @Test
    void mongoDbOutagesAreConnectionProblemsNotDuplicates() {
        assertThat(Breakers.isMongoDbOutage(new DataAccessResourceFailureException("down"))).isTrue();
        assertThat(Breakers.isMongoDbOutage(new DuplicateKeyException("dup"))).isFalse();
    }

    @Test
    void mongoDbUnavailableLooksThroughWrappers() {
        CallNotPermittedException refused = CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("mongodb"));
        assertThat(Breakers.mongoDbUnavailable(new RuntimeException("listener failed", refused))).isTrue();
        assertThat(Breakers.mongoDbUnavailable(
                new RuntimeException(new DataAccessResourceFailureException("down")))).isTrue();
        assertThat(Breakers.mongoDbUnavailable(new RuntimeException(new DuplicateKeyException("dup")))).isFalse();
    }

    @Test
    void guardedFallsBackWithoutCallingWhileOpen() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("redis");
        Guarded guarded = new Guarded(breaker, LoggerFactory.getLogger(BreakersTest.class));
        breaker.transitionToOpenState();
        AtomicBoolean called = new AtomicBoolean();

        String result = guarded.callOr("test", () -> { called.set(true); return "live"; }, "fallback");

        assertThat(result).isEqualTo("fallback");
        assertThat(called).isFalse();
        assertThatThrownBy(() -> guarded.call(() -> "live")).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void guardedFallsBackOnFailureAndRecordsIt() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("redis");
        Guarded guarded = new Guarded(breaker, LoggerFactory.getLogger(BreakersTest.class));

        String result = guarded.callOr("test", () -> { throw new IllegalStateException("down"); }, "fallback");

        assertThat(result).isEqualTo("fallback");
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }
}
