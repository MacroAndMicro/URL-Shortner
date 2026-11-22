package com.Url_Shortener.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * Names of the circuit breakers, and which failures count against each.
 *
 * Only failures that mean "the service is unreachable or struggling" count.
 * Outcomes that are part of normal operation (a DynamoDB conditional write
 * that finds the key taken, a duplicate click event) never open a breaker.
 */
public final class Breakers {

    public static final String DYNAMODB = "dynamodb";
    public static final String REDIS = "redis";
    public static final String KAFKA = "kafka";
    public static final String MONGODB = "mongodb";

    private Breakers() {
    }

    /** Network problems and server-side (5xx) errors; not 4xx such as a failed condition. */
    public static boolean isDynamoDbOutage(Throwable e) {
        return e instanceof SdkClientException
                || (e instanceof AwsServiceException ase && ase.statusCode() >= 500);
    }

    /** Lost connection, timeout or similar; not duplicate keys or bad queries. */
    public static boolean isMongoDbOutage(Throwable e) {
        return e instanceof DataAccessResourceFailureException
                || e instanceof TransientDataAccessException
                || e instanceof com.mongodb.MongoSocketException
                || e instanceof com.mongodb.MongoTimeoutException;
    }

    /** Every Redis error is about reaching Redis: the app never asks it for something invalid. */
    public static boolean isRedisOutage(Throwable e) {
        return true;
    }

    /** Every failed send means Kafka could not take the event. */
    public static boolean isKafkaOutage(Throwable e) {
        return true;
    }

    /** True if e, or anything that caused it, is the breaker refusing the call. */
    public static boolean refusedByOpenBreaker(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CallNotPermittedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if e, or anything that caused it, means MongoDB is unavailable:
     * an outage or its breaker being open. Such click events are retried until
     * MongoDB is back rather than sent to the dead-letter topic.
     */
    public static boolean mongoDbUnavailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CallNotPermittedException || isMongoDbOutage(t)) {
                return true;
            }
        }
        return false;
    }
}
