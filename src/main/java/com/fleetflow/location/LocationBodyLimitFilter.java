package com.fleetflow.location;

import java.io.IOException;
import java.io.ByteArrayInputStream;

import com.fleetflow.api.SecurityProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
class LocationBodyLimitFilter {
    private static final int MAX_API_BYTES = 1024 * 1024;
    private static final int MAX_LOCATION_BYTES = 1024;

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> locationBodyLimit(SecurityProblemWriter errors) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                String path = request.getServletPath();
                if (path == null || path.isEmpty()) {
                    path = request.getRequestURI().substring(request.getContextPath().length());
                }
                return !path.startsWith("/api/v1/")
                        || !java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod());
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws IOException, ServletException {
                String path = request.getServletPath();
                if (path == null || path.isEmpty()) {
                    path = request.getRequestURI().substring(request.getContextPath().length());
                }
                int maxBytes = path.equals("/api/v1/drivers/me/location")
                        ? MAX_LOCATION_BYTES : MAX_API_BYTES;
                if (request.getContentLengthLong() > maxBytes) {
                    tooLarge(request, response, errors);
                    return;
                }
                // Content-Length may be absent (for example with chunked transfer encoding).
                byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
                if (body.length > maxBytes) {
                    tooLarge(request, response, errors);
                    return;
                }
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    private ServletInputStream replay;
                    @Override
                    public ServletInputStream getInputStream() throws IOException {
                        if (replay != null) return replay;
                        ByteArrayInputStream bytes = new ByteArrayInputStream(body);
                        replay = new ServletInputStream() {
                            private ReadListener listener;
                            private boolean complete;
                            @Override public boolean isFinished() { return bytes.available() == 0; }
                            @Override public boolean isReady() { return true; }
                            @Override public void setReadListener(ReadListener next) {
                                listener = next;
                                try {
                                    if (isFinished()) signalComplete();
                                    else listener.onDataAvailable();
                                } catch (IOException failure) {
                                    listener.onError(failure);
                                }
                            }
                            @Override public int read() throws IOException {
                                int value = bytes.read();
                                if (value == -1) signalComplete();
                                return value;
                            }
                            private void signalComplete() throws IOException {
                                if (listener != null && !complete) {
                                    complete = true;
                                    listener.onAllDataRead();
                                }
                            }
                        };
                        return replay;
                    }

                    @Override
                    public java.io.BufferedReader getReader() throws IOException {
                        return new java.io.BufferedReader(new java.io.InputStreamReader(
                                getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    }

                    @Override public int getContentLength() { return body.length; }
                    @Override public long getContentLengthLong() { return body.length; }
                }, response);
            }

            private void tooLarge(HttpServletRequest request, HttpServletResponse response,
                                  SecurityProblemWriter errors) throws IOException {
                errors.write(request, response, 413, "PAYLOAD_TOO_LARGE",
                        "Request body exceeds the allowed size");
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(-90);
        return bean;
    }
}
