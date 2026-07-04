package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.AgentAuditResponse;
import com.smartfinance.agent.service.AgentAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent-audit")
public class AgentAuditController {

    private final AgentAuditService agentAuditService;

    public AgentAuditController(AgentAuditService agentAuditService) {
        this.agentAuditService = agentAuditService;
    }

    @GetMapping("/overview")
    public Result<AgentAuditResponse> overview(@RequestAttribute Long userId) {
        return Result.success(agentAuditService.overview(userId));
    }
}
