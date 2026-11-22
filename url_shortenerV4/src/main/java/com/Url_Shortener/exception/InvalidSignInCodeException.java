package com.Url_Shortener.exception;

/** A Google/GitHub sign-in code that is unknown, already used or expired (401). */
public class InvalidSignInCodeException extends RuntimeException {
    public InvalidSignInCodeException(String message) {
        super(message);
    }
}
