package com.fleetflow.location;

import java.io.IOException;

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
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    private ServletInputStream limited;
                    @Override
                    public ServletInputStream getInputStream() throws IOException {
                        if (limited != null) return limited;
                        ServletInputStream delegate = super.getInputStream();
                        limited = new ServletInputStream() {
                            private int count;
                            @Override public boolean isFinished() { return delegate.isFinished(); }
                            @Override public boolean isReady() { return delegate.isReady(); }
                            @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
                            @Override public int read() throws IOException {
                                int value = delegate.read();
                                if (value != -1 && ++count > MAX_BYTES) throw new IOException("Location body limit");
                                return value;
                            }
                        };
                        return limited;
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
