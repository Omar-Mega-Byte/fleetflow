package com.fleetflow.api;

import org.springframework.http.HttpStatus;

public class ApiFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiFailure(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
