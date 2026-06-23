package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smartfinance.agent.entity.AgentRun;
import com.smartfinance.agent.entity.AgentRunStep;
import com.smartfinance.agent.mapper.AgentRunMapper;
import com.smartfinance.agent.mapper.AgentRunStepMapper;
import com.smartfinance.agent.service.AgentRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AgentRunServiceImpl implements AgentRunService {

    private final AgentRunMapper agentRunMapper;
    private final AgentRunStepMapper agentRunStepMapper;

    public AgentRunServiceImpl(AgentRunMapper agentRunMapper, AgentRunStepMapper agentRunStepMapper) {
        this.agentRunMapper = agentRunMapper;
        this.agentRunStepMapper = agentRunStepMapper;
    }

    @Override
    public void startRun(Long userId, String traceId, String query) {
        if (isBlank(traceId)) {
            return;
        }
        try {
            AgentRun run = new AgentRun();
            run.setUserId(userId);
            run.setTraceId(traceId);
            run.setQuery(query);
            run.setStatus("RUNNING");
            run.setStartedAt(LocalDateTime.now());
            agentRunMapper.insert(run);
        } catch (Exception e) {
            log.warn("Start agent run failed: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    @Override
    public void completeRun(String traceId, String finalAnswer) {
        finishRun(traceId, "COMPLETED", null, finalAnswer);
    }

    @Override
    public void failRun(String traceId, String errorMessage, String finalAnswer) {
        finishRun(traceId, "FAILED", errorMessage, finalAnswer);
    }

    @Override
    public void recordStepStarted(Long userId, String traceId, int stepNumber, String summary, String toolName) {
        if (isBlank(traceId)) {
            return;
        }
        try {
            AgentRunStep existing = findStep(traceId, stepNumber);
            if (existing != null) {
                existing.setSummary(truncate(summary, 500));
                existing.setToolName(truncate(toolName, 100));
                existing.setStatus("RUNNING");
                if (existing.getStartedAt() == null) {
                    existing.setStartedAt(LocalDateTime.now());
                }
                agentRunStepMapper.updateById(existing);
                return;
            }

            AgentRunStep step = new AgentRunStep();
            step.setUserId(userId);
            step.setTraceId(traceId);
            step.setStepNumber(stepNumber);
            step.setSummary(truncate(summary, 500));
            step.setToolName(truncate(toolName, 100));
            step.setStatus("RUNNING");
            step.setStartedAt(LocalDateTime.now());
            agentRunStepMapper.insert(step);
        } catch (Exception e) {
            log.warn("Start agent run step failed: traceId={}, step={}, error={}", traceId, stepNumber, e.getMessage());
        }
    }

    @Override
    public void recordStepFinished(Long userId,
                                   String traceId,
                                   int stepNumber,
                                   String summary,
                                   String toolName,
                                   String input,
                                   boolean success,
                                   String observationSummary,
                                   String errorMessage) {
        if (isBlank(traceId)) {
            return;
        }
        try {
            AgentRunStep step = findStep(traceId, stepNumber);
            if (step == null) {
                step = new AgentRunStep();
                step.setUserId(userId);
                step.setTraceId(traceId);
                step.setStepNumber(stepNumber);
                step.setStartedAt(LocalDateTime.now());
            }
            step.setSummary(truncate(firstNonBlank(summary, observationSummary), 500));
            step.setToolName(truncate(toolName, 100));
            step.setInput(truncate(input, 2000));
            step.setSuccess(success ? 1 : 0);
            step.setObservationSummary(truncate(observationSummary, 500));
            step.setErrorMessage(truncate(errorMessage, 500));
            step.setStatus(success ? "COMPLETED" : "FAILED");
            step.setFinishedAt(LocalDateTime.now());

            if (step.getId() == null) {
                agentRunStepMapper.insert(step);
            } else {
                agentRunStepMapper.updateById(step);
            }
        } catch (Exception e) {
            log.warn("Finish agent run step failed: traceId={}, step={}, error={}", traceId, stepNumber, e.getMessage());
        }
    }

    @Override
    public Map<String, List<Map<String, Object>>> stepsByTraceIds(List<String> traceIds) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        if (traceIds == null || traceIds.isEmpty()) {
            return grouped;
        }
        List<String> cleanTraceIds = traceIds.stream()
                .filter(id -> !isBlank(id))
                .distinct()
                .toList();
        if (cleanTraceIds.isEmpty()) {
            return grouped;
        }
        List<AgentRunStep> steps = agentRunStepMapper.selectList(
                new LambdaQueryWrapper<AgentRunStep>()
                        .in(AgentRunStep::getTraceId, cleanTraceIds)
                        .orderByAsc(AgentRunStep::getTraceId)
                        .orderByAsc(AgentRunStep::getStepNumber));
        for (AgentRunStep step : steps) {
            grouped.computeIfAbsent(step.getTraceId(), ignored -> new ArrayList<>()).add(toHistoryStep(step));
        }
        return grouped;
    }

    private void finishRun(String traceId, String status, String errorMessage, String finalAnswer) {
        if (isBlank(traceId)) {
            return;
        }
        try {
            AgentRun run = agentRunMapper.selectOne(new LambdaQueryWrapper<AgentRun>()
                    .eq(AgentRun::getTraceId, traceId)
                    .last("LIMIT 1"));
            LocalDateTime finishedAt = LocalDateTime.now();
            Long durationMs = run == null || run.getStartedAt() == null
                    ? null
                    : Duration.between(run.getStartedAt(), finishedAt).toMillis();

            agentRunMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                    .eq(AgentRun::getTraceId, traceId)
                    .set(AgentRun::getStatus, status)
                    .set(AgentRun::getFinishedAt, finishedAt)
                    .set(AgentRun::getDurationMs, durationMs)
                    .set(AgentRun::getFinalAnswer, finalAnswer)
                    .set(AgentRun::getErrorMessage, truncate(errorMessage, 500)));
        } catch (Exception e) {
            log.warn("Finish agent run failed: traceId={}, status={}, error={}", traceId, status, e.getMessage());
        }
    }

    private AgentRunStep findStep(String traceId, int stepNumber) {
        return agentRunStepMapper.selectOne(new LambdaQueryWrapper<AgentRunStep>()
                .eq(AgentRunStep::getTraceId, traceId)
                .eq(AgentRunStep::getStepNumber, stepNumber)
                .last("LIMIT 1"));
    }

    private Map<String, Object> toHistoryStep(AgentRunStep step) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("stepNumber", step.getStepNumber());
        item.put("summary", step.getSummary());
        item.put("tool", step.getToolName());
        item.put("success", step.getSuccess() != null && step.getSuccess() == 1);
        item.put("status", toFrontendStatus(step.getStatus(), step.getSuccess()));
        item.put("observationSummary", step.getObservationSummary());
        return item;
    }

    private static String toFrontendStatus(String status, Integer success) {
        if ("RUNNING".equals(status)) {
            return "running";
        }
        if ("FAILED".equals(status) || (success != null && success == 0)) {
            return "failed";
        }
        return "done";
    }

    private static String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
