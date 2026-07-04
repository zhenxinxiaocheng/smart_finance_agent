package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.AgentReflectionAcceptRequest;
import com.smartfinance.agent.entity.AgentReflection;
import com.smartfinance.agent.service.AgentReflectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent-reflections")
public class AgentReflectionController {

    private final AgentReflectionService agentReflectionService;

    public AgentReflectionController(AgentReflectionService agentReflectionService) {
        this.agentReflectionService = agentReflectionService;
    }

    @GetMapping
    public Result<List<AgentReflection>> list(@RequestAttribute Long userId,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String suggestionType) {
        return Result.success(agentReflectionService.list(userId, status, suggestionType));
    }

    @PostMapping("/{id}/accept")
    public Result<AgentReflection> accept(@RequestAttribute Long userId,
                                          @PathVariable Long id,
                                          @RequestBody(required = false) AgentReflectionAcceptRequest request) {
        return Result.success(agentReflectionService.accept(userId, id, request));
    }

    @PostMapping("/{id}/dismiss")
    public Result<AgentReflection> dismiss(@RequestAttribute Long userId, @PathVariable Long id) {
        return Result.success(agentReflectionService.dismiss(userId, id));
    }
}
