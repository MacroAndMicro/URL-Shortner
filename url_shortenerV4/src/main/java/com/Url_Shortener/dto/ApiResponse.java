package com.Url_Shortener.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private int status;
    private T data;
    private String message;
    private String timestamp;

    private ApiResponse(int status, T data, String message) {
        this.status = status;
        this.data = data;
        this.message = message;
        this.timestamp = java.time.OffsetDateTime.now().toString();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, data, "Success");
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(200, data, message);
    }

    public static <T> ApiResponse<T> error(int status, String message) {
        return new ApiResponse<>(status, null, message);
    }

    public static <T> ApiResponse<T> error(int status, String message, T data) {
        return new ApiResponse<>(status, data, message);
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
