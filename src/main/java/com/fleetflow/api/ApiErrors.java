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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ApiProblem> domain(ApiFailure failure, HttpServletRequest request) {
        return reply(failure.status(), failure.code(), failure.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        List<String> fields = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField()).distinct().sorted().limit(20).toList();
        return reply(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request validation failed", request, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, IllegalArgumentException.class, DateTimeException.class})
    ResponseEntity<ApiProblem> malformed(Exception failure, HttpServletRequest request) {
        return reply(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request is malformed or out of range", request, List.of());
    }

    @ExceptionHandler({DataIntegrityViolationException.class, org.hibernate.exception.ConstraintViolationException.class})
    ResponseEntity<ApiProblem> constraint(RuntimeException failure, HttpServletRequest request) {
        return reply(HttpStatus.CONFLICT, "DATA_CONFLICT", "Resource conflicts with stored data", request, List.of());
    }

    @ExceptionHandler({DataAccessException.class, PersistenceException.class})
    ResponseEntity<ApiProblem> unavailable(RuntimeException failure, HttpServletRequest request) {
        return reply(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Database is unavailable", request, List.of());
    }

    private ResponseEntity<ApiProblem> reply(HttpStatus status, String code, String message,
                                          HttpServletRequest request, List<String> fields) {
        return ResponseEntity.status(status).body(ApiProblem.of(code, message, request, fields));
    }
}
