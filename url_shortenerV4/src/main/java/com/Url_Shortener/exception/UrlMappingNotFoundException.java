package com.Url_Shortener.exception;

public class UrlMappingNotFoundException extends RuntimeException {
    public UrlMappingNotFoundException(String message) {
        super(message);
    }
}
