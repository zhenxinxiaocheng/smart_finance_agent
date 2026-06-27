package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.service.AgentRunService;
import com.smartfinance.agent.service.SkillInvocationRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/agent-runs")
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final SkillInvocationRecordService skillInvocationRecordService;

    public AgentRunController(AgentRunService agentRunService,
                              SkillInvocationRecordService skillInvocationRecordService) {
        this.agentRunService = agentRunService;
        this.skillInvocationRecordService = skillInvocationRecordService;
    }

    @GetMapping("/{traceId}")
    public Result<Map<String, Object>> detail(@RequestAttribute Long userId,
                                              @PathVariable String traceId) {
        Map<String, Object> detail = new LinkedHashMap<>(agentRunService.detail(userId, traceId));
        detail.put("skillInvocations", skillInvocationRecordService.listByTraceId(userId, traceId));
        return Result.success(detail);
    }
}
