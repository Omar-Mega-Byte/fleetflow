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
    private static final int MAX_BYTES = 1024;

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> locationBodyLimit(SecurityProblemWriter errors) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getMethod().equals("POST")
                        || !request.getRequestURI().equals("/api/v1/drivers/me/location");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws IOException, ServletException {
                if (request.getContentLengthLong() > MAX_BYTES) {
                    errors.write(request, response, 400, "INVALID_REQUEST", "Location request is too large");
                    return;
                }
                // Content-Length may be absent (for example with chunked transfer encoding).
                byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) {
                    errors.write(request, response, 400, "INVALID_REQUEST", "Location request is too large");
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
                }, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(-90);
        return bean;
    }
}
