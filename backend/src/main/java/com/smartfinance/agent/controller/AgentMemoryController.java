package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.AgentMemoryPreferencesRequest;
import com.smartfinance.agent.dto.AgentMemoryPreferencesResponse;
import com.smartfinance.agent.dto.AgentMemoryRequest;
import com.smartfinance.agent.entity.AgentMemory;
import com.smartfinance.agent.service.AgentMemoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent-memories")
public class AgentMemoryController {

    private final AgentMemoryService agentMemoryService;

    public AgentMemoryController(AgentMemoryService agentMemoryService) {
        this.agentMemoryService = agentMemoryService;
    }

    @GetMapping
    public Result<List<AgentMemory>> list(@RequestAttribute Long userId) {
        return Result.success(agentMemoryService.list(userId));
    }

    @GetMapping("/preferences")
    public Result<AgentMemoryPreferencesResponse> getPreferences(@RequestAttribute Long userId) {
        return Result.success(agentMemoryService.getPreferences(userId));
    }

    @PutMapping("/preferences")
    public Result<AgentMemoryPreferencesResponse> updatePreferences(@RequestAttribute Long userId,
                                                                    @RequestBody AgentMemoryPreferencesRequest request) {
        return Result.success(agentMemoryService.updatePreferences(userId, request));
    }

    @PostMapping
    public Result<AgentMemory> create(@RequestAttribute Long userId,
                                      @Valid @RequestBody AgentMemoryRequest request) {
        return Result.success(agentMemoryService.createManual(userId, request));
    }

    @PutMapping("/{id}/disabled")
    public Result<AgentMemory> setDisabled(@RequestAttribute Long userId,
                                           @PathVariable Long id,
                                           @RequestParam boolean disabled) {
        return Result.success(agentMemoryService.setDisabled(userId, id, disabled));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute Long userId, @PathVariable Long id) {
        agentMemoryService.delete(userId, id);
        return Result.success();
    }

    @DeleteMapping
    public Result<Void> reset(@RequestAttribute Long userId) {
        agentMemoryService.reset(userId);
        return Result.success();
    }
}
