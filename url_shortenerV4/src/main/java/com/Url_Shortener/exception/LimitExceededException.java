package com.Url_Shortener.exception;

import org.springframework.http.HttpStatus;

/**
 * A plan or guest limit stops the request. The message is shown to the user.
 */
public class LimitExceededException extends RuntimeException {

    private final HttpStatus status;

    public LimitExceededException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    /** The plan does not allow this (e.g. no more links, no analytics). */
    public static LimitExceededException forbidden(String message) {
        return new LimitExceededException(message, HttpStatus.FORBIDDEN);
    }

    /** Too many attempts; may succeed later. */
    public static LimitExceededException tooMany(String message) {
        return new LimitExceededException(message, HttpStatus.TOO_MANY_REQUESTS);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
