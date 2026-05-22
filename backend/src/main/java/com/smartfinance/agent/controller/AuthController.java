package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.JwtUtils;
import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.LoginRequest;
import com.smartfinance.agent.dto.RegisterRequest;
import com.smartfinance.agent.entity.User;
import com.smartfinance.agent.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtUtils jwtUtils;

    public AuthController(UserService userService, JwtUtils jwtUtils) {
        this.userService = userService;
        this.jwtUtils = jwtUtils;
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(
                request.getUsername(),
                request.getPassword(),
                request.getNickname(),
                request.getEmail()
        );
        String token = jwtUtils.generateToken(user.getId(), user.getUsername());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("user", toUserInfo(user));
        return Result.success(data);
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.login(request.getUsername(), request.getPassword());
        String token = jwtUtils.generateToken(user.getId(), user.getUsername());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("user", toUserInfo(user));
        return Result.success(data);
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> getCurrentUser(@RequestAttribute Long userId) {
        User user = userService.getById(userId);
        return Result.success(toUserInfo(user));
    }

    private Map<String, Object> toUserInfo(User user) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", user.getId());
        info.put("username", user.getUsername());
        info.put("nickname", user.getNickname());
        info.put("email", user.getEmail());
        info.put("avatar", user.getAvatar());
        return info;
    }
}
