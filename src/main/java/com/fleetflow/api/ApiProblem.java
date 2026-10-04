package com.fleetflow.api;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

public record ApiProblem(String code, String message, String path, Instant timestamp,
                         String correlationId, List<String> fields) {
    public static ApiProblem of(String code, String message, HttpServletRequest request, List<String> fields) {
        Object value = request.getAttribute("phase1CorrelationId");
        String correlationId = value instanceof String id ? id : "unavailable";
        return new ApiProblem(code, message, request.getRequestURI(), Instant.now(), correlationId, fields);
    }
}
