package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.context.ContextUsageSnapshot;
import com.smartfinance.agent.dto.AgentContextConfig;
import com.smartfinance.agent.dto.AgentContextCompressRequest;
import com.smartfinance.agent.entity.AgentContextSummary;
import com.smartfinance.agent.service.AgentContextCompressionService;
import com.smartfinance.agent.service.AgentContextConfigService;
import com.smartfinance.agent.service.AgentContextUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent-context")
public class AgentContextController {

    private final AgentContextCompressionService compressionService;
    private final AgentContextConfigService configService;
    private final AgentContextUsageService usageService;

    public AgentContextController(AgentContextCompressionService compressionService,
                                  AgentContextConfigService configService,
                                  AgentContextUsageService usageService) {
        this.compressionService = compressionService;
        this.configService = configService;
        this.usageService = usageService;
    }

    @PostMapping("/compress")
    public Result<AgentContextSummary> compress(@RequestAttribute Long userId,
                                                @RequestBody(required = false) AgentContextCompressRequest request) {
        Long conversationId = request == null ? null : request.getConversationId();
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId is required");
        }
        String traceId = request == null ? null : request.getTraceId();
        String scope = request == null ? null : request.getScope();
        return Result.success(compressionService.compressConversation(userId, conversationId, traceId, scope));
    }

    @GetMapping("/config")
    public Result<AgentContextConfig> getConfig(@RequestAttribute Long userId) {
        return Result.success(configService.getCurrentConfig(userId));
    }

    @GetMapping("/usage")
    public Result<ContextUsageSnapshot> getUsage(@RequestAttribute Long userId,
                                                 @RequestParam Long conversationId) {
        return Result.success(usageService.previewConversation(userId, conversationId));
    }
}
