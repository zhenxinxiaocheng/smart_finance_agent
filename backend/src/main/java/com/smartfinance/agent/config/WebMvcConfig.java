package com.smartfinance.agent.config;

import com.smartfinance.agent.interceptor.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final com.smartfinance.agent.ratelimit.ApiRateLimiter rateLimiter;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public WebMvcConfig(JwtInterceptor jwtInterceptor,
                        com.smartfinance.agent.ratelimit.ApiRateLimiter rateLimiter,
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.jwtInterceptor = jwtInterceptor;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .order(0)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register"
                );
        registry.addInterceptor(new com.smartfinance.agent.ratelimit.RateLimitInterceptor(rateLimiter, objectMapper))
                .order(1).addPathPatterns("/api/**");
    }
}
