package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.AgentScheduleRequest;
import com.smartfinance.agent.entity.AgentSchedule;
import com.smartfinance.agent.entity.AgentScheduleRun;
import com.smartfinance.agent.service.AgentScheduleService;
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
@RequestMapping("/api/agent-schedules")
public class AgentScheduleController {

    private final AgentScheduleService scheduleService;

    public AgentScheduleController(AgentScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping
    public Result<List<AgentSchedule>> list(@RequestAttribute Long userId) {
        return Result.success(scheduleService.list(userId));
    }

    @PostMapping
    public Result<AgentSchedule> create(@RequestAttribute Long userId,
                                        @RequestBody AgentScheduleRequest request) {
        return Result.success(scheduleService.create(
                userId,
                request.getName(),
                request.getDescription(),
                request.getCronExpression(),
                request.getTaskQuery(),
                request.getTimezone()));
    }

    @PutMapping("/{id}/enabled")
    public Result<AgentSchedule> setEnabled(@RequestAttribute Long userId,
                                            @PathVariable Long id,
                                            @RequestParam boolean enabled) {
        return Result.success(scheduleService.setEnabled(userId, id, enabled));
    }

    @PostMapping("/{id}/retry")
    public Result<AgentSchedule> retryNow(@RequestAttribute Long userId,
                                          @PathVariable Long id) {
        return Result.success(scheduleService.retryNow(userId, id));
    }

    @GetMapping("/{id}/runs")
    public Result<List<AgentScheduleRun>> listRuns(@RequestAttribute Long userId,
                                                   @PathVariable Long id) {
        return Result.success(scheduleService.listRuns(userId, id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute Long userId, @PathVariable Long id) {
        scheduleService.delete(userId, id);
        return Result.success();
    }
}
