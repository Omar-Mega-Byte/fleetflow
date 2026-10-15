package com.fleetflow.api;

import java.time.DateTimeException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ApiProblem> domain(ApiFailure failure, HttpServletRequest request) {
        ResponseEntity<ApiProblem> response = reply(failure.status(), failure.code(),
                failure.getMessage(), request, List.of());
        if (failure.retryAfterSeconds() == null) return response;
        return ResponseEntity.status(failure.status())
                .header("Retry-After", Long.toString(Math.max(1, failure.retryAfterSeconds())))
                .body(response.getBody());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiProblem> missingResource(NoResourceFoundException failure, HttpServletRequest request) {
        return reply(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found", request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        List<String> fields = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField()).distinct().sorted().limit(20).toList();
        return reply(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request validation failed", request, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            IllegalArgumentException.class, DateTimeException.class})
    ResponseEntity<ApiProblem> malformed(Exception failure, HttpServletRequest request) {
        return reply(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request is malformed or out of range", request, List.of());
    }

    @ExceptionHandler({DataIntegrityViolationException.class, org.hibernate.exception.ConstraintViolationException.class})
    ResponseEntity<ApiProblem> constraint(RuntimeException failure, HttpServletRequest request) {
        return reply(HttpStatus.CONFLICT, "DATA_CONFLICT", "Resource conflicts with stored data", request, List.of());
    }

    @ExceptionHandler({DataAccessException.class, PersistenceException.class,
            CannotCreateTransactionException.class})
    ResponseEntity<ApiProblem> unavailable(RuntimeException failure, HttpServletRequest request) {
        return reply(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Database is unavailable", request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiProblem> method(HttpRequestMethodNotSupportedException failure, HttpServletRequest request) {
        return reply(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed", request, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiProblem> media(HttpMediaTypeNotSupportedException failure, HttpServletRequest request) {
        return reply(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported request format", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> unexpected(Exception failure, HttpServletRequest request) {
        // Exception messages and stack traces can contain SQL parameters or untrusted payloads.
        log.atError().addKeyValue("event", "http.unexpected_failure")
                .addKeyValue("failureType", failure.getClass().getSimpleName()).log("Request failed unexpectedly");
        return reply(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Request could not be completed", request, List.of());
    }

    private ResponseEntity<ApiProblem> reply(HttpStatus status, String code, String message,
                                          HttpServletRequest request, List<String> fields) {
        return ResponseEntity.status(status).body(ApiProblem.of(code, message, request, fields));
    }
}
