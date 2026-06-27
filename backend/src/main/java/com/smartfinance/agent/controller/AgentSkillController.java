package com.smartfinance.agent.controller;

import com.smartfinance.agent.agent.ToolRegistry;
import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.AgentSkillEnabledRequest;
import com.smartfinance.agent.dto.AgentSkillInstallRequest;
import com.smartfinance.agent.entity.AgentSkill;
import com.smartfinance.agent.entity.SkillInvocationRecord;
import com.smartfinance.agent.service.AgentSkillService;
import com.smartfinance.agent.service.SkillInvocationRecordService;
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
@RequestMapping("/api/agent-skills")
public class AgentSkillController {

    private final AgentSkillService agentSkillService;
    private final SkillInvocationRecordService invocationRecordService;
    private final ToolRegistry toolRegistry;

    public AgentSkillController(AgentSkillService agentSkillService,
                                SkillInvocationRecordService invocationRecordService,
                                ToolRegistry toolRegistry) {
        this.agentSkillService = agentSkillService;
        this.invocationRecordService = invocationRecordService;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public Result<List<AgentSkill>> list(@RequestAttribute Long userId) {
        toolRegistry.syncBuiltInSkills(userId);
        return Result.success(agentSkillService.list(userId));
    }

    @GetMapping("/{id}")
    public Result<AgentSkill> detail(@RequestAttribute Long userId, @PathVariable Long id) {
        toolRegistry.syncBuiltInSkills(userId);
        return Result.success(agentSkillService.detail(userId, id));
    }

    @PostMapping("/install")
    public Result<AgentSkill> install(@RequestAttribute Long userId,
                                      @Valid @RequestBody AgentSkillInstallRequest request) {
        return Result.success(agentSkillService.install(userId, request));
    }

    @PutMapping("/{id}/enabled")
    public Result<AgentSkill> setEnabled(@RequestAttribute Long userId,
                                         @PathVariable Long id,
                                         @RequestBody AgentSkillEnabledRequest request) {
        return Result.success(agentSkillService.setEnabled(userId, id, request.isEnabled()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute Long userId, @PathVariable Long id) {
        agentSkillService.delete(userId, id);
        return Result.success();
    }

    @GetMapping("/invocations")
    public Result<List<SkillInvocationRecord>> invocations(@RequestAttribute Long userId,
                                                           @RequestParam(required = false) String skillName,
                                                           @RequestParam(defaultValue = "50") int limit) {
        return Result.success(invocationRecordService.list(userId, skillName, limit));
    }
}
