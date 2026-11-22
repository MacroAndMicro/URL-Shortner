package com.Url_Shortener.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * Runs calls to one outside service through its circuit breaker.
 *
 * call/run throw on failure (including CallNotPermittedException while the
 * breaker is open). callOr/runOr fall back instead, logging a warning for a
 * real failure but only at debug level for a call skipped by an open
 * breaker, so an outage does not log one warning per request.
 */
public final class Guarded {

    private final CircuitBreaker breaker;
    private final Logger log;

    public Guarded(CircuitBreaker breaker, Logger log) {
        this.breaker = breaker;
        this.log = log;
    }

    public <T> T call(Supplier<T> operation) {
        return breaker.executeSupplier(operation);
    }

    public void run(Runnable operation) {
        breaker.executeRunnable(operation);
    }

    /** Result of operation, or fallback if it fails or the breaker is open. */
    public <T> T callOr(String what, Supplier<T> operation, T fallback) {
        try {
            return call(operation);
        } catch (RuntimeException e) {
            logFailure(what, e);
            return fallback;
        }
    }

    /** Runs operation; a failure or open breaker is logged and otherwise ignored. */
    public void runOr(String what, Runnable operation) {
        try {
            run(operation);
        } catch (RuntimeException e) {
            logFailure(what, e);
        }
    }

    public void logFailure(String what, Throwable e) {
        if (Breakers.refusedByOpenBreaker(e)) {
            log.debug("{} skipped: {} circuit breaker is open", what, breaker.getName());
        } else {
            log.warn("{} failed: {}", what, e.getMessage());
        }
    }

    public CircuitBreaker breaker() {
        return breaker;
    }
}
