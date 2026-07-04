package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.dto.AgentMemoryRequest;
import com.smartfinance.agent.dto.AgentReflectionAcceptRequest;
import com.smartfinance.agent.dto.CustomSkillDraftRequest;
import com.smartfinance.agent.entity.AgentReflection;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.mapper.AgentReflectionMapper;
import com.smartfinance.agent.service.AgentReflectionService;
import com.smartfinance.agent.service.AgentRunService;
import com.smartfinance.agent.service.PendingActionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentReflectionServiceImpl implements AgentReflectionService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_DISMISSED = "DISMISSED";

    private final AgentReflectionMapper reflectionMapper;
    private final AgentRunService agentRunService;
    private final PendingActionService pendingActionService;
    private final ObjectMapper objectMapper;

    public AgentReflectionServiceImpl(AgentReflectionMapper reflectionMapper,
                                      AgentRunService agentRunService,
                                      PendingActionService pendingActionService,
                                      ObjectMapper objectMapper) {
        this.reflectionMapper = reflectionMapper;
        this.agentRunService = agentRunService;
        this.pendingActionService = pendingActionService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<AgentReflection> reflectRun(Long userId, String traceId) {
        requireUser(userId);
        if (isBlank(traceId)) {
            throw new IllegalArgumentException("traceId is required");
        }
        Map<String, Object> detail = agentRunService.detail(userId, traceId);
        List<AgentReflection> reflections = new ArrayList<>();
        if (isFailedRun(detail)) {
            reflections.add(createReflection(
                    userId,
                    traceId,
                    "RISK_WARNING",
                    "Agent 运行失败风险",
                    "本次运行出现失败或工具异常：" + defaultText(text(detail, "errorMessage"), "请查看运行轨迹确认原因"),
                    detail
            ));
        } else if (hasScheduleIntent(text(detail, "query")) && !usedTool(detail, "create_agent_schedule")) {
            reflections.add(createReflection(
                    userId,
                    traceId,
                    "SCHEDULE_CANDIDATE",
                    "可沉淀为周期任务",
                    "这次需求像是一个可重复执行的监控或复盘任务：" + text(detail, "query"),
                    detail
            ));
        } else if (hasMemoryIntent(text(detail, "query"))) {
            reflections.add(createReflection(
                    userId,
                    traceId,
                    "MEMORY_CANDIDATE",
                    "可沉淀为长期记忆",
                    "这次需求像是一个明确的偏好或长期指令，可考虑通过待确认动作写入记忆：" + text(detail, "query"),
                    detail
            ));
        } else if (hasSkillIntent(text(detail, "query")) && !usedTool(detail, "create_custom_skill")) {
            reflections.add(createReflection(
                    userId,
                    traceId,
                    "SKILL_CANDIDATE",
                    "可沉淀为自定义 Skill",
                    "这次需求像是一个稳定工作流，可考虑通过待确认动作沉淀为 Skill：" + text(detail, "query"),
                    detail
            ));
        }
        return reflections;
    }

    @Override
    @Transactional
    public AgentReflection reflectScheduleFailure(Long userId,
                                                  Long scheduleId,
                                                  String taskQuery,
                                                  String errorMessage,
                                                  Integer consecutiveFailures) {
        requireUser(userId);
        if (scheduleId == null) {
            throw new IllegalArgumentException("scheduleId is required");
        }
        String syntheticTraceId = "schedule-" + scheduleId + "-failure";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("traceId", syntheticTraceId);
        evidence.put("scheduleId", scheduleId);
        evidence.put("query", defaultText(taskQuery, ""));
        evidence.put("status", "FAILED");
        evidence.put("errorMessage", defaultText(errorMessage, "周期任务执行失败"));
        evidence.put("consecutiveFailures", consecutiveFailures == null ? 0 : consecutiveFailures);
        return createReflection(
                userId,
                syntheticTraceId,
                "RISK_WARNING",
                "周期任务失败风险",
                "周期任务执行失败，连续失败 " + (consecutiveFailures == null ? 0 : consecutiveFailures)
                        + " 次：" + defaultText(errorMessage, "请检查任务配置或模型可用性"),
                evidence
        );
    }

    @Override
    public List<AgentReflection> list(Long userId, String status, String suggestionType) {
        requireUser(userId);
        LambdaQueryWrapper<AgentReflection> wrapper = new LambdaQueryWrapper<AgentReflection>()
                .eq(AgentReflection::getUserId, userId)
                .eq(AgentReflection::getDeleted, 0)
                .orderByDesc(AgentReflection::getCreatedAt);
        if (!isBlank(status)) {
            wrapper.eq(AgentReflection::getStatus, status.trim());
        }
        if (!isBlank(suggestionType)) {
            wrapper.eq(AgentReflection::getSuggestionType, suggestionType.trim());
        }
        return reflectionMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public AgentReflection accept(Long userId, Long reflectionId, AgentReflectionAcceptRequest request) {
        AgentReflection reflection = loadOwned(userId, reflectionId);
        if (!STATUS_OPEN.equals(reflection.getStatus())) {
            throw new IllegalArgumentException("Reflection has already been handled");
        }
        PendingAction action;
        String sourceTraceId = sourceTraceId(reflection);
        if ("SKILL_CANDIDATE".equals(reflection.getSuggestionType())) {
            action = pendingActionService.prepareCustomSkill(
                    userId,
                    toSkillDraft(reflection),
                    reflection.getId(),
                    sourceTraceId
            );
        } else if ("MEMORY_CANDIDATE".equals(reflection.getSuggestionType())) {
            action = pendingActionService.prepareMemory(
                    userId,
                    toMemoryRequest(reflection),
                    reflection.getId(),
                    sourceTraceId
            );
        } else if ("SCHEDULE_CANDIDATE".equals(reflection.getSuggestionType())) {
            ScheduleDraft draft = toScheduleDraft(reflection, request);
            action = pendingActionService.prepareSchedule(
                    userId,
                    draft.name(),
                    draft.description(),
                    draft.cronExpression(),
                    draft.taskQuery(),
                    draft.timezone(),
                    reflection.getId(),
                    sourceTraceId
            );
        } else {
            throw new IllegalArgumentException("This reflection cannot be accepted automatically yet");
        }
        reflection.setStatus(STATUS_ACCEPTED);
        reflection.setPayload(toJson(acceptedPayload(reflection, action)));
        reflectionMapper.updateById(reflection);
        return reflection;
    }

    @Transactional
    public AgentReflection accept(Long userId, Long reflectionId) {
        return accept(userId, reflectionId, null);
    }

    @Override
    @Transactional
    public AgentReflection dismiss(Long userId, Long reflectionId) {
        AgentReflection reflection = loadOwned(userId, reflectionId);
        reflection.setStatus(STATUS_DISMISSED);
        reflectionMapper.updateById(reflection);
        return reflection;
    }

    private CustomSkillDraftRequest toSkillDraft(AgentReflection reflection) {
        Map<String, Object> payload = readPayload(reflection.getPayload());
        String query = text(payload, "query");
        CustomSkillDraftRequest request = new CustomSkillDraftRequest();
        request.setName(truncate(defaultText(reflection.getTitle(), "运行反思 Skill"), 80));
        request.setDescription(truncate(defaultText(reflection.getSummary(), "由 Agent 运行反思生成的 Skill 候选"), 240));
        request.setTriggerText(truncate(defaultText(query, reflection.getSummary()), 240));
        request.setInstructionText("""
                复用这次对话中已经稳定下来的工作方式。
                当用户提出相似需求时，先确认目标和边界，再执行只读分析或已有安全工具调用。
                涉及写入、安装、周期任务、外部信息或高风险财务建议时，继续走用户确认流程。
                """.stripIndent().trim());
        request.setBoundTools(List.of());
        request.setCategory("Reflection");
        request.setRiskLevel("READ_ONLY");
        return request;
    }

    private ScheduleDraft toScheduleDraft(AgentReflection reflection, AgentReflectionAcceptRequest request) {
        Map<String, Object> payload = readPayload(reflection.getPayload());
        String query = defaultText(text(payload, "query"), reflection.getSummary());
        if (request != null && (!isBlank(request.getCronExpression()) || !isBlank(request.getTaskQuery()))) {
            String taskQuery = defaultText(request.getTaskQuery(), query);
            return new ScheduleDraft(
                    truncate(defaultText(request.getName(), "运行反思周期任务"), 80),
                    truncate(defaultText(request.getDescription(), defaultText(reflection.getSummary(), taskQuery)), 240),
                    requireText(request.getCronExpression(), "cronExpression is required"),
                    taskQuery,
                    defaultText(request.getTimezone(), "Asia/Shanghai")
            );
        }
        String cronExpression = inferCronExpression(query);
        return new ScheduleDraft(
                "运行反思周期任务",
                truncate(defaultText(reflection.getSummary(), query), 240),
                cronExpression,
                query,
                "Asia/Shanghai"
        );
    }

    private AgentMemoryRequest toMemoryRequest(AgentReflection reflection) {
        Map<String, Object> payload = readPayload(reflection.getPayload());
        String traceId = defaultText(text(payload, "traceId"), defaultText(reflection.getTraceId(), "reflection"));
        String query = defaultText(text(payload, "query"), reflection.getSummary());
        AgentMemoryRequest request = new AgentMemoryRequest();
        request.setMemoryType(resolveMemoryType(query));
        request.setMemoryKey("reflection_" + normalizeKey(traceId));
        request.setMemoryValue(truncate(defaultText(reflection.getSummary(), query), 500));
        return request;
    }

    private Map<String, Object> readPayload(String payload) {
        if (isBlank(payload)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Reflection payload parsing failed", e);
        }
    }

    private AgentReflection createReflection(Long userId,
                                             String traceId,
                                             String suggestionType,
                                             String title,
                                             String summary,
                                             Map<String, Object> evidence) {
        AgentReflection existing = findExistingReflection(userId, traceId, suggestionType);
        if (existing != null) {
            return existing;
        }
        AgentReflection reflection = new AgentReflection();
        reflection.setUserId(userId);
        reflection.setTraceId(traceId);
        reflection.setSuggestionType(suggestionType);
        reflection.setTitle(title);
        reflection.setSummary(truncate(summary, 500));
        reflection.setPayload(toJson(evidencePayload(traceId, evidence)));
        reflection.setStatus(STATUS_OPEN);
        reflection.setDeleted(0);
        reflectionMapper.insert(reflection);
        return reflection;
    }

    private AgentReflection findExistingReflection(Long userId, String traceId, String suggestionType) {
        return reflectionMapper.selectOne(new LambdaQueryWrapper<AgentReflection>()
                .eq(AgentReflection::getUserId, userId)
                .eq(AgentReflection::getTraceId, traceId)
                .eq(AgentReflection::getSuggestionType, suggestionType)
                .eq(AgentReflection::getDeleted, 0)
                .last("limit 1"));
    }

    private AgentReflection loadOwned(Long userId, Long reflectionId) {
        requireUser(userId);
        AgentReflection reflection = reflectionMapper.selectById(reflectionId);
        if (reflection == null || !userId.equals(reflection.getUserId())
                || reflection.getDeleted() != null && reflection.getDeleted() == 1) {
            throw new IllegalArgumentException("Reflection does not exist");
        }
        return reflection;
    }

    private Map<String, Object> evidencePayload(String traceId, Map<String, Object> evidence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", traceId);
        payload.put("query", text(evidence, "query"));
        payload.put("status", text(evidence, "status"));
        payload.put("errorMessage", text(evidence, "errorMessage"));
        payload.put("finalAnswer", text(evidence, "finalAnswer"));
        payload.put("steps", evidence.get("steps"));
        for (Map.Entry<String, Object> entry : evidence.entrySet()) {
            payload.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return payload;
    }

    private Map<String, Object> acceptedPayload(AgentReflection reflection, PendingAction action) {
        Map<String, Object> payload = new LinkedHashMap<>(readPayload(reflection.getPayload()));
        if (action != null) {
            payload.put("pendingActionId", action.getId());
            payload.put("pendingActionType", action.getActionType());
            payload.put("pendingActionStatus", action.getStatus());
        }
        return payload;
    }

    private String sourceTraceId(AgentReflection reflection) {
        if (!isBlank(reflection.getTraceId())) {
            return reflection.getTraceId();
        }
        return text(readPayload(reflection.getPayload()), "traceId");
    }

    private boolean isFailedRun(Map<String, Object> detail) {
        if ("FAILED".equalsIgnoreCase(text(detail, "status")) || !isBlank(text(detail, "errorMessage"))) {
            return true;
        }
        for (Map<String, Object> step : steps(detail)) {
            if ("failed".equalsIgnoreCase(text(step, "status")) || !isBlank(text(step, "errorMessage"))) {
                return true;
            }
            Object success = step.get("success");
            if (Boolean.FALSE.equals(success) || Integer.valueOf(0).equals(success)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasScheduleIntent(String query) {
        String clean = defaultText(query, "");
        return clean.contains("每天")
                || clean.contains("每周")
                || clean.contains("每月")
                || clean.contains("定期")
                || clean.contains("提醒我")
                || clean.contains("自动帮我")
                || clean.contains("监控");
    }

    private String inferCronExpression(String query) {
        String clean = defaultText(query, "");
        if (clean.contains("每天")) {
            return "0 0 9 * * ?";
        }
        if (clean.contains("每周")) {
            return "0 0 9 ? * MON";
        }
        if (clean.contains("每月")) {
            return "0 0 9 1 * ?";
        }
        throw new IllegalArgumentException("Schedule frequency is ambiguous");
    }

    private boolean hasSkillIntent(String query) {
        String clean = defaultText(query, "").toLowerCase();
        return clean.contains("做成skill")
                || clean.contains("做成 skill")
                || clean.contains("自定义skill")
                || clean.contains("自定义 skill")
                || clean.contains("每次")
                || clean.contains("以后")
                || clean.contains("固定流程")
                || clean.contains("都先");
    }

    private boolean hasMemoryIntent(String query) {
        String clean = defaultText(query, "").toLowerCase();
        return clean.contains("记住")
                || clean.contains("以后回答")
                || clean.contains("以后都")
                || clean.contains("偏好")
                || clean.contains("习惯")
                || clean.contains("prefer")
                || clean.contains("remember");
    }

    private String resolveMemoryType(String query) {
        String clean = defaultText(query, "").toLowerCase();
        if (clean.contains("回答") || clean.contains("简短") || clean.contains("详细")
                || clean.contains("中文") || clean.contains("english")) {
            return "RESPONSE_STYLE";
        }
        return "AGENT_PREFERENCE";
    }

    private static String normalizeKey(String value) {
        String clean = defaultText(value, "reflection").trim().toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("(^_+|_+$)", "");
        return clean.isBlank() ? "reflection" : truncate(clean, 80);
    }

    private boolean usedTool(Map<String, Object> detail, String toolName) {
        for (Map<String, Object> step : steps(detail)) {
            if (toolName.equalsIgnoreCase(text(step, "tool"))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(Map<String, Object> detail) {
        Object value = detail.get("steps");
        if (!(value instanceof List<?> rawSteps)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rawSteps) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Reflection payload generation failed", e);
        }
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String requireText(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
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

    private record ScheduleDraft(String name,
                                 String description,
                                 String cronExpression,
                                 String taskQuery,
                                 String timezone) {}
}
