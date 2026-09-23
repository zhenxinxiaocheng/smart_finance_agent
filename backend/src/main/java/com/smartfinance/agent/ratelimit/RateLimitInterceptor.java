package com.smartfinance.agent.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class RateLimitInterceptor implements HandlerInterceptor {
    private static final String CHECKED = RateLimitInterceptor.class.getName() + ".checked";
    private final ApiRateLimiter limiter;
    private final ObjectMapper mapper;

    public RateLimitInterceptor(ApiRateLimiter limiter, ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equals(request.getMethod()) || request.getAttribute(CHECKED) != null
                || !(handler instanceof HandlerMethod method)) return true;
        RateLimited annotation = method.getMethodAnnotation(RateLimited.class);
        if (annotation == null) return true;
        if (!annotation.booleanParameter().isEmpty()) {
            String value = request.getParameter(annotation.booleanParameter());
            try {
                if (!Boolean.TRUE.equals(DefaultConversionService.getSharedInstance().convert(value, Boolean.class))) return true;
            } catch (IllegalArgumentException exception) {
                return true; // MVC will report malformed parameters before invoking the controller.
            }
        }
        Object userId = request.getAttribute("userId");
        if (annotation.value() != RateLimitScope.LOGIN && userId == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write(mapper.writeValueAsString(Result.unauthorized("请先登录")));
            return false;
        }
        // Do not trust client-supplied forwarding headers. Container proxy configuration owns remoteAddr.
        String identity = annotation.value() == RateLimitScope.LOGIN ? request.getRemoteAddr() : userId.toString();
        ApiRateLimiter.Decision decision = limiter.acquire(annotation.value(), identity);
        if (decision.allowed()) {
            request.setAttribute(CHECKED, true);
            return true;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(mapper.writeValueAsString(Result.error(429,
                "操作太频繁，请在 " + decision.retryAfterSeconds() + " 秒后重试")));
        return false;
    }
}
