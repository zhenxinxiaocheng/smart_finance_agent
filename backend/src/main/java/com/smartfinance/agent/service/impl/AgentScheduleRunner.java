package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smartfinance.agent.agent.ReActAgentService;
import com.smartfinance.agent.dto.ReActResult;
import com.smartfinance.agent.entity.AgentSchedule;
import com.smartfinance.agent.entity.AgentScheduleRun;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.AgentScheduleMapper;
import com.smartfinance.agent.mapper.AgentScheduleRunMapper;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.AgentReflectionService;
import com.smartfinance.agent.util.AgentCronExpressions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
public class AgentScheduleRunner {

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final int MAX_ANSWER_LENGTH = 2000;
    private static final int LOCK_TTL_MINUTES = 30;
    private static final int FAILURE_CIRCUIT_BREAKER_THRESHOLD = 3;
    private static final int FIRST_FAILURE_RETRY_DELAY_MINUTES = 15;
    private static final int REPEATED_FAILURE_RETRY_DELAY_MINUTES = 60;

    private final AgentScheduleMapper scheduleMapper;
    private final AgentScheduleRunMapper scheduleRunMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ReActAgentService reActAgentService;
    private final AgentReflectionService agentReflectionService;

    public AgentScheduleRunner(AgentScheduleMapper scheduleMapper,
                               AgentScheduleRunMapper scheduleRunMapper,
                               ChatMessageMapper chatMessageMapper,
                               ReActAgentService reActAgentService,
                               AgentReflectionService agentReflectionService) {
        this.scheduleMapper = scheduleMapper;
        this.scheduleRunMapper = scheduleRunMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.reActAgentService = reActAgentService;
        this.agentReflectionService = agentReflectionService;
    }

    @Transactional
    public int executeDueSchedules() {
        LocalDateTime now = LocalDateTime.now();
        List<AgentSchedule> dueSchedules = scheduleMapper.selectList(new LambdaQueryWrapper<AgentSchedule>()
                .eq(AgentSchedule::getEnabled, 1)
                .eq(AgentSchedule::getDeleted, 0)
                .isNotNull(AgentSchedule::getNextRunAt)
                .le(AgentSchedule::getNextRunAt, now)
                .and(wrapper -> wrapper.isNull(AgentSchedule::getLockUntil)
                        .or()
                        .le(AgentSchedule::getLockUntil, now)));

        int executed = 0;
        for (AgentSchedule schedule : dueSchedules) {
            if (!acquireExecutionLock(schedule, now)) {
                continue;
            }
            executeOne(schedule, now);
            executed++;
        }
        return executed;
    }

    @Transactional
    public AgentSchedule executeNow(Long userId, Long scheduleId) {
        AgentSchedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null || schedule.getDeleted() != null && schedule.getDeleted() == 1
                || userId == null || !userId.equals(schedule.getUserId())) {
            throw new IllegalArgumentException("Schedule does not exist");
        }

        LocalDateTime now = LocalDateTime.now();
        if (!acquireManualExecutionLock(schedule, now)) {
            throw new IllegalStateException("Schedule is already running");
        }
        schedule.setEnabled(1);
        schedule.setConsecutiveFailures(0);
        schedule.setNextRunAt(now);
        executeOne(schedule, now);
        return schedule;
    }

    @Scheduled(fixedDelay = 60_000)
    public void scheduledExecutionLoop() {
        try {
            int executed = executeDueSchedules();
            if (executed > 0) {
                log.info("Executed {} agent schedule(s)", executed);
            }
        } catch (Exception e) {
            log.warn("Agent schedule execution loop failed: {}", e.getMessage(), e);
        }
    }

    private void executeOne(AgentSchedule schedule, LocalDateTime now) {
        LocalDateTime startedAt = LocalDateTime.now();
        String errorMessage = null;
        try {
            ReActResult result = reActAgentService.run(schedule.getUserId(), schedule.getTaskQuery());
            schedule.setTraceId(result == null ? null : result.getTraceId());
            schedule.setLastStatus("SUCCESS");
            schedule.setLastAnswer(truncate(result == null ? "" : result.getFinalAnswer(), MAX_ANSWER_LENGTH));
            schedule.setConsecutiveFailures(0);
            schedule.setEnabled(1);
        } catch (Exception e) {
            schedule.setTraceId(null);
            schedule.setLastStatus("FAILED");
            errorMessage = e.getMessage();
            schedule.setLastAnswer(truncate(errorMessage, MAX_ANSWER_LENGTH));
            int failures = (schedule.getConsecutiveFailures() == null ? 0 : schedule.getConsecutiveFailures()) + 1;
            schedule.setConsecutiveFailures(failures);
            if (failures >= FAILURE_CIRCUIT_BREAKER_THRESHOLD) {
                schedule.setEnabled(0);
            } else {
                schedule.setEnabled(1);
            }
            log.warn("Agent schedule failed: id={}, userId={}", schedule.getId(), schedule.getUserId(), e);
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        schedule.setLastRunAt(finishedAt);
        schedule.setRunCount((schedule.getRunCount() == null ? 0 : schedule.getRunCount()) + 1);
        if (schedule.getEnabled() != null && schedule.getEnabled() == 0) {
            schedule.setNextRunAt(null);
        } else if ("FAILED".equals(schedule.getLastStatus())) {
            schedule.setNextRunAt(finishedAt.plusMinutes(retryDelayMinutes(schedule.getConsecutiveFailures())));
        } else {
            schedule.setNextRunAt(nextRunAt(schedule.getCronExpression(), schedule.getTimezone(), now.plusSeconds(1)));
        }
        schedule.setLockUntil(null);
        scheduleMapper.updateById(schedule);
        saveRunHistory(schedule, startedAt, finishedAt, errorMessage);
        saveChatReply(schedule, errorMessage);
        if ("FAILED".equals(schedule.getLastStatus())) {
            agentReflectionService.reflectScheduleFailure(
                    schedule.getUserId(),
                    schedule.getId(),
                    schedule.getTaskQuery(),
                    errorMessage,
                    schedule.getConsecutiveFailures()
            );
        }
    }

    private boolean acquireExecutionLock(AgentSchedule schedule, LocalDateTime now) {
        AgentSchedule update = new AgentSchedule();
        update.setLockUntil(now.plusMinutes(LOCK_TTL_MINUTES));
        int updated = scheduleMapper.update(update, new LambdaUpdateWrapper<AgentSchedule>()
                .eq(AgentSchedule::getId, schedule.getId())
                .eq(AgentSchedule::getEnabled, 1)
                .eq(AgentSchedule::getDeleted, 0)
                .le(AgentSchedule::getNextRunAt, now)
                .and(wrapper -> wrapper.isNull(AgentSchedule::getLockUntil)
                        .or()
                        .le(AgentSchedule::getLockUntil, now)));
        return updated > 0;
    }

    private boolean acquireManualExecutionLock(AgentSchedule schedule, LocalDateTime now) {
        AgentSchedule update = new AgentSchedule();
        update.setLockUntil(now.plusMinutes(LOCK_TTL_MINUTES));
        int updated = scheduleMapper.update(update, new LambdaUpdateWrapper<AgentSchedule>()
                .eq(AgentSchedule::getId, schedule.getId())
                .eq(AgentSchedule::getUserId, schedule.getUserId())
                .eq(AgentSchedule::getDeleted, 0)
                .and(wrapper -> wrapper.isNull(AgentSchedule::getLockUntil)
                        .or()
                        .le(AgentSchedule::getLockUntil, now)));
        return updated > 0;
    }

    private void saveRunHistory(AgentSchedule schedule,
                                LocalDateTime startedAt,
                                LocalDateTime finishedAt,
                                String errorMessage) {
        AgentScheduleRun run = new AgentScheduleRun();
        run.setScheduleId(schedule.getId());
        run.setUserId(schedule.getUserId());
        run.setTraceId(schedule.getTraceId());
        run.setStatus(schedule.getLastStatus());
        run.setAnswer(schedule.getLastAnswer());
        run.setErrorMessage(truncate(errorMessage, MAX_ANSWER_LENGTH));
        run.setStartedAt(startedAt);
        run.setFinishedAt(finishedAt);
        run.setDurationMs(Math.max(0L, java.time.Duration.between(startedAt, finishedAt).toMillis()));
        scheduleRunMapper.insert(run);
    }

    private void saveChatReply(AgentSchedule schedule, String errorMessage) {
        ChatMessage message = new ChatMessage();
        message.setUserId(schedule.getUserId());
        message.setRole("ASSISTANT");
        message.setTraceId(schedule.getTraceId());
        message.setContent(chatReplyContent(schedule, errorMessage));
        chatMessageMapper.insert(message);
    }

    private String chatReplyContent(AgentSchedule schedule, String errorMessage) {
        String title = schedule.getName() == null || schedule.getName().isBlank()
                ? "周期任务"
                : schedule.getName().trim();
        if ("FAILED".equals(schedule.getLastStatus())) {
            return "周期任务「%s」执行失败：%s".formatted(title, truncate(errorMessage, MAX_ANSWER_LENGTH));
        }
        return "周期任务「%s」已执行完成。\n\n%s".formatted(title, truncate(schedule.getLastAnswer(), MAX_ANSWER_LENGTH));
    }

    private static LocalDateTime nextRunAt(String cronExpression, String timezone, LocalDateTime from) {
        CronExpression cron = CronExpression.parse(AgentCronExpressions.normalize(cronExpression));
        ZoneId zoneId = ZoneId.of(timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone.trim());
        ZonedDateTime next = cron.next(from.atZone(zoneId));
        if (next == null) {
            throw new IllegalArgumentException("Cron expression has no next execution time");
        }
        return next.toLocalDateTime();
    }

    private static int retryDelayMinutes(Integer consecutiveFailures) {
        int failures = consecutiveFailures == null ? 1 : consecutiveFailures;
        return failures <= 1 ? FIRST_FAILURE_RETRY_DELAY_MINUTES : REPEATED_FAILURE_RETRY_DELAY_MINUTES;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
