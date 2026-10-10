package com.fleetflow;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds a safe correlation ID to responses and logs without trusting arbitrary header text. */
public class CorrelationIdFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    static final String HEADER = "X-Correlation-ID";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String correlationId = supplied != null && VALID_ID.matcher(supplied).matches()
                ? supplied : UUID.randomUUID().toString();
        response.setHeader(HEADER, correlationId);
        request.setAttribute("phase1CorrelationId", correlationId);
        long started = System.nanoTime();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
            try {
                filterChain.doFilter(request, response);
            } finally {
                Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = matched instanceof String pattern ? pattern : "unmatched";
                log.atInfo().addKeyValue("event", "http.request.completed")
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("route", route)
                        .addKeyValue("status", response.getStatus())
                        .addKeyValue("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                        .log("HTTP request completed");
            }
        }
    }
}
