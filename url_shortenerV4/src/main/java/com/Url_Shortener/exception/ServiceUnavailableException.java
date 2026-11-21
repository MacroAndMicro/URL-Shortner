package com.Url_Shortener.exception;

/** A dependency needed for this request is down; the message is shown to the user. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
