package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.dto.AgentAuditEvent;
import com.smartfinance.agent.dto.AgentAuditResponse;
import com.smartfinance.agent.dto.AgentAuditSummary;
import com.smartfinance.agent.entity.AgentReflection;
import com.smartfinance.agent.entity.AgentSchedule;
import com.smartfinance.agent.entity.AgentScheduleRun;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.mapper.AgentReflectionMapper;
import com.smartfinance.agent.mapper.AgentScheduleMapper;
import com.smartfinance.agent.mapper.AgentScheduleRunMapper;
import com.smartfinance.agent.mapper.PendingActionMapper;
import com.smartfinance.agent.service.AgentAuditService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentAuditServiceImpl implements AgentAuditService {

    private static final int EVENT_LIMIT = 50;

    private final AgentReflectionMapper reflectionMapper;
    private final PendingActionMapper pendingActionMapper;
    private final AgentScheduleMapper scheduleMapper;
    private final AgentScheduleRunMapper scheduleRunMapper;
    private final ObjectMapper objectMapper;

    public AgentAuditServiceImpl(AgentReflectionMapper reflectionMapper,
                                 PendingActionMapper pendingActionMapper,
                                 AgentScheduleMapper scheduleMapper,
                                 AgentScheduleRunMapper scheduleRunMapper,
                                 ObjectMapper objectMapper) {
        this.reflectionMapper = reflectionMapper;
        this.pendingActionMapper = pendingActionMapper;
        this.scheduleMapper = scheduleMapper;
        this.scheduleRunMapper = scheduleRunMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentAuditResponse overview(Long userId) {
        requireUser(userId);
        List<AgentReflection> reflections = reflectionMapper.selectList(new LambdaQueryWrapper<AgentReflection>()
                .eq(AgentReflection::getUserId, userId)
                .eq(AgentReflection::getDeleted, 0));
        List<PendingAction> actions = pendingActionMapper.selectList(new LambdaQueryWrapper<PendingAction>()
                .eq(PendingAction::getUserId, userId));
        List<AgentSchedule> schedules = scheduleMapper.selectList(new LambdaQueryWrapper<AgentSchedule>()
                .eq(AgentSchedule::getUserId, userId)
                .eq(AgentSchedule::getDeleted, 0));
        List<AgentScheduleRun> scheduleRuns = scheduleRunMapper.selectList(new LambdaQueryWrapper<AgentScheduleRun>()
                .eq(AgentScheduleRun::getUserId, userId)
                .orderByDesc(AgentScheduleRun::getStartedAt)
                .last("LIMIT " + EVENT_LIMIT));

        AgentAuditResponse response = new AgentAuditResponse();
        response.setSummary(summary(reflections, actions, schedules));
        response.setEvents(events(reflections, actions, schedules, scheduleRuns));
        return response;
    }

    private AgentAuditSummary summary(List<AgentReflection> reflections,
                                      List<PendingAction> actions,
                                      List<AgentSchedule> schedules) {
        AgentAuditSummary summary = new AgentAuditSummary();
        summary.setOpenReflections((int) reflections.stream().filter(item -> "OPEN".equals(item.getStatus())).count());
        summary.setPendingActions((int) actions.stream().filter(item -> "PENDING".equals(item.getStatus())).count());
        summary.setFailedSchedules((int) schedules.stream()
                .filter(item -> "FAILED".equals(item.getLastStatus()) || isCircuitBroken(item))
                .count());
        summary.setActiveSchedules((int) schedules.stream()
                .filter(item -> value(item.getEnabled()) == 1)
                .count());
        return summary;
    }

    private List<AgentAuditEvent> events(List<AgentReflection> reflections,
                                         List<PendingAction> actions,
                                         List<AgentSchedule> schedules,
                                         List<AgentScheduleRun> scheduleRuns) {
        List<AgentAuditEvent> events = new ArrayList<>();
        reflections.stream().map(this::reflectionEvent).forEach(events::add);
        actions.stream().map(this::actionEvent).forEach(events::add);
        schedules.stream().map(this::scheduleEvent).forEach(events::add);
        scheduleRuns.stream().map(this::scheduleRunEvent).forEach(events::add);
        return events.stream()
                .sorted(Comparator.comparing(AgentAuditEvent::getTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(EVENT_LIMIT)
                .toList();
    }

    private AgentAuditEvent reflectionEvent(AgentReflection reflection) {
        AgentAuditEvent event = baseEvent(
                "reflection-" + reflection.getId(),
                "REFLECTION",
                defaultText(reflection.getTitle(), reflectionTypeLabel(reflection.getSuggestionType())),
                defaultText(reflection.getSummary(), reflectionTypeLabel(reflection.getSuggestionType())),
                reflectionStatusLabel(reflection.getStatus()),
                reflection.getStatus(),
                "RISK_WARNING".equals(reflection.getSuggestionType()) ? "warning" : statusSeverity(reflection.getStatus()),
                firstTime(reflection.getUpdatedAt(), reflection.getCreatedAt()),
                reflection.getTraceId()
        );
        event.setTarget(target("/reflections", Map.of("status", "", "reflectionId", reflection.getId())));
        return event;
    }

    private AgentAuditEvent actionEvent(PendingAction action) {
        Map<String, Object> payload = readPayload(action.getPayload());
        String result = "";
        if (!isBlank(text(payload, "resultEntityType"))) {
            result = " -> " + resultEntityLabel(text(payload, "resultEntityType"))
                    + " #" + defaultText(text(payload, "resultEntityId"), "-");
        }
        AgentAuditEvent event = baseEvent(
                "action-" + action.getId(),
                "ACTION",
                defaultText(action.getTitle(), actionTypeLabel(action.getActionType())),
                actionTypeLabel(action.getActionType()) + " · " + actionStatusLabel(action.getStatus()) + result,
                actionStatusLabel(action.getStatus()),
                action.getStatus(),
                statusSeverity(action.getStatus()),
                firstTime(action.getUpdatedAt(), action.getCreatedAt()),
                text(payload, "sourceTraceId")
        );
        event.setTarget(target("/pending-actions", Map.of("actionId", action.getId())));
        return event;
    }

    private AgentAuditEvent scheduleEvent(AgentSchedule schedule) {
        int failures = value(schedule.getConsecutiveFailures());
        String status = isCircuitBroken(schedule)
                ? "已熔断"
                : "FAILED".equals(schedule.getLastStatus())
                ? "最近失败"
                : value(schedule.getEnabled()) == 1 ? "运行中" : "已停用";
        AgentAuditEvent event = baseEvent(
                "schedule-" + schedule.getId(),
                "SCHEDULE",
                defaultText(schedule.getName(), "周期任务"),
                status + " · 已运行 " + value(schedule.getRunCount()) + " 次 · 连续失败 " + failures + " 次",
                status,
                defaultText(schedule.getLastStatus(), value(schedule.getEnabled()) == 1 ? "ENABLED" : "DISABLED"),
                "已熔断".equals(status) || "最近失败".equals(status) ? "warning" : value(schedule.getEnabled()) == 1 ? "success" : "muted",
                firstTime(schedule.getUpdatedAt(), schedule.getLastRunAt(), schedule.getCreatedAt()),
                schedule.getTraceId()
        );
        event.setTarget(target("/schedules", Map.of("scheduleId", schedule.getId())));
        return event;
    }

    private AgentAuditEvent scheduleRunEvent(AgentScheduleRun run) {
        boolean success = "SUCCESS".equals(run.getStatus());
        String status = success ? "成功" : "FAILED".equals(run.getStatus()) ? "失败" : defaultText(run.getStatus(), "未知状态");
        String summary = success
                ? "执行成功 · " + value(run.getDurationMs()) + "ms"
                : "执行失败 · " + defaultText(firstText(run.getErrorMessage(), run.getAnswer()), "无错误摘要");
        AgentAuditEvent event = baseEvent(
                "schedule-run-" + run.getId(),
                "SCHEDULE_RUN",
                "周期任务执行",
                summary,
                status,
                run.getStatus(),
                success ? "success" : "FAILED".equals(run.getStatus()) ? "warning" : "muted",
                firstTime(run.getFinishedAt(), run.getStartedAt(), run.getCreatedAt()),
                run.getTraceId()
        );
        event.setTarget(target("/schedules", Map.of("scheduleId", run.getScheduleId(), "runId", run.getId())));
        return event;
    }

    private AgentAuditEvent baseEvent(String id,
                                      String source,
                                      String title,
                                      String summary,
                                      String status,
                                      String rawStatus,
                                      String severity,
                                      LocalDateTime time,
                                      String traceId) {
        AgentAuditEvent event = new AgentAuditEvent();
        event.setId(id);
        event.setSource(source);
        event.setTitle(title);
        event.setSummary(summary);
        event.setStatus(status);
        event.setRawStatus(rawStatus);
        event.setSeverity(severity);
        event.setTime(time);
        event.setTraceId(isBlank(traceId) ? null : traceId);
        return event;
    }

    private AgentAuditEvent.AgentAuditTarget target(String path, Map<String, Object> query) {
        AgentAuditEvent.AgentAuditTarget target = new AgentAuditEvent.AgentAuditTarget();
        target.setPath(path);
        target.setQuery(new LinkedHashMap<>(query));
        return target;
    }

    private Map<String, Object> readPayload(String payload) {
        if (isBlank(payload)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String reflectionTypeLabel(String type) {
        return switch (defaultText(type, "")) {
            case "MEMORY_CANDIDATE" -> "记忆候选";
            case "SKILL_CANDIDATE" -> "Skill 候选";
            case "SCHEDULE_CANDIDATE" -> "周期任务候选";
            case "RISK_WARNING" -> "风险警告";
            default -> defaultText(type, "未知类型");
        };
    }

    private static String reflectionStatusLabel(String status) {
        return switch (defaultText(status, "")) {
            case "OPEN" -> "待处理";
            case "ACCEPTED" -> "已采纳";
            case "DISMISSED" -> "已忽略";
            default -> defaultText(status, "未知状态");
        };
    }

    private static String actionTypeLabel(String type) {
        return switch (defaultText(type, "")) {
            case "RECORD_TRANSACTION" -> "记录交易";
            case "SET_BUDGET" -> "设置预算";
            case "INSTALL_CUSTOM_SKILL" -> "安装自定义 Skill";
            case "INSTALL_AGENT_MEMORY" -> "写入长期记忆";
            case "CREATE_AGENT_SCHEDULE" -> "创建周期任务";
            default -> defaultText(type, "未知动作");
        };
    }

    private static String actionStatusLabel(String status) {
        return switch (defaultText(status, "")) {
            case "PENDING" -> "待确认";
            case "CONFIRMED" -> "已确认";
            case "CANCELLED" -> "已取消";
            default -> defaultText(status, "未知状态");
        };
    }

    private static String resultEntityLabel(String type) {
        return switch (defaultText(type, "")) {
            case "TRANSACTION" -> "交易记录";
            case "BUDGET" -> "预算";
            case "AGENT_SKILL" -> "Agent Skill";
            case "AGENT_MEMORY" -> "长期记忆";
            case "AGENT_SCHEDULE" -> "周期任务";
            default -> defaultText(type, "对象");
        };
    }

    private static String statusSeverity(String status) {
        return switch (defaultText(status, "")) {
            case "PENDING", "OPEN" -> "pending";
            case "CONFIRMED", "ACCEPTED" -> "success";
            case "CANCELLED", "DISMISSED" -> "muted";
            default -> "muted";
        };
    }

    private static boolean isCircuitBroken(AgentSchedule schedule) {
        return value(schedule.getEnabled()) == 0 && value(schedule.getConsecutiveFailures()) >= 3;
    }

    private static LocalDateTime firstTime(LocalDateTime... values) {
        for (LocalDateTime value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
    }
}
