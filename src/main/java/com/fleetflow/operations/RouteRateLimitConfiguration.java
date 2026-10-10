package com.fleetflow.operations;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class RouteRateLimitConfiguration implements WebMvcConfigurer {
    private final RouteRateLimits limits;

    RouteRateLimitConfiguration(RouteRateLimits limits) {
        this.limits = limits;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(limits).addPathPatterns("/api/v1/**");
    }
}
