package com.smartfinance.agent.interceptor;

import com.smartfinance.agent.common.JwtUtils;
import com.smartfinance.agent.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper;

    public JwtInterceptor(JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write(objectMapper.writeValueAsString(Result.unauthorized("未登录或token已过期")));
            return false;
        }

        String token = authHeader.substring(7);
        if (!jwtUtils.isTokenValid(token)) {
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write(objectMapper.writeValueAsString(Result.unauthorized("token无效或已过期")));
            return false;
        }

        Long userId = jwtUtils.getUserIdFromToken(token);
        request.setAttribute("userId", userId);
        return true;
    }
}
