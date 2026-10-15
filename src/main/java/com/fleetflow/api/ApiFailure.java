package com.fleetflow.api;

import org.springframework.http.HttpStatus;

public class ApiFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Long retryAfterSeconds;

    public ApiFailure(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiFailure(HttpStatus status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
