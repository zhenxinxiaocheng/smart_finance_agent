package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.entity.AgentSchedule;
import com.smartfinance.agent.entity.AgentScheduleRun;
import com.smartfinance.agent.mapper.AgentScheduleMapper;
import com.smartfinance.agent.mapper.AgentScheduleRunMapper;
import com.smartfinance.agent.service.AgentScheduleService;
import com.smartfinance.agent.util.AgentCronExpressions;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
public class AgentScheduleServiceImpl implements AgentScheduleService {

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private final AgentScheduleMapper scheduleMapper;
    private final AgentScheduleRunMapper scheduleRunMapper;
    private final AgentScheduleRunner scheduleRunner;

    public AgentScheduleServiceImpl(AgentScheduleMapper scheduleMapper,
                                    AgentScheduleRunMapper scheduleRunMapper,
                                    AgentScheduleRunner scheduleRunner) {
        this.scheduleMapper = scheduleMapper;
        this.scheduleRunMapper = scheduleRunMapper;
        this.scheduleRunner = scheduleRunner;
    }

    @Override
    public List<AgentSchedule> list(Long userId) {
        return scheduleMapper.selectList(new LambdaQueryWrapper<AgentSchedule>()
                .eq(AgentSchedule::getUserId, userId)
                .eq(AgentSchedule::getDeleted, 0)
                .orderByDesc(AgentSchedule::getCreatedAt));
    }

    @Override
    @Transactional
    public AgentSchedule create(Long userId,
                                String name,
                                String description,
                                String cronExpression,
                                String taskQuery,
                                String timezone) {
        requireUser(userId);
        String cleanCron = requireText(AgentCronExpressions.normalize(cronExpression), "cronExpression");
        String cleanTask = requireText(taskQuery, "taskQuery");

        AgentSchedule schedule = new AgentSchedule();
        schedule.setUserId(userId);
        schedule.setName(truncate(requireText(name, "name"), MAX_NAME_LENGTH));
        schedule.setDescription(truncate(description, MAX_DESCRIPTION_LENGTH));
        schedule.setCronExpression(cleanCron);
        schedule.setTimezone(cleanTimezone(timezone));
        schedule.setTaskQuery(cleanTask);
        schedule.setEnabled(1);
        schedule.setRunCount(0);
        schedule.setConsecutiveFailures(0);
        schedule.setNextRunAt(nextRunAt(cleanCron, schedule.getTimezone(), LocalDateTime.now()));
        schedule.setLockUntil(null);
        schedule.setDeleted(0);
        scheduleMapper.insert(schedule);
        return schedule;
    }

    @Override
    @Transactional
    public AgentSchedule update(Long userId,
                                Long scheduleId,
                                String name,
                                String description,
                                String cronExpression,
                                String taskQuery,
                                String timezone) {
        AgentSchedule schedule = loadOwned(userId, scheduleId);
        String cleanCron = requireText(AgentCronExpressions.normalize(cronExpression), "cronExpression");
        String cleanTask = requireText(taskQuery, "taskQuery");
        String cleanTimezone = cleanTimezone(timezone);

        schedule.setName(truncate(requireText(name, "name"), MAX_NAME_LENGTH));
        schedule.setDescription(truncate(description, MAX_DESCRIPTION_LENGTH));
        schedule.setCronExpression(cleanCron);
        schedule.setTimezone(cleanTimezone);
        schedule.setTaskQuery(cleanTask);
        schedule.setLockUntil(null);
        if (schedule.getEnabled() == null || schedule.getEnabled() == 1) {
            schedule.setEnabled(1);
            schedule.setNextRunAt(nextRunAt(cleanCron, cleanTimezone, LocalDateTime.now()));
        } else {
            schedule.setNextRunAt(null);
        }
        scheduleMapper.updateById(schedule);
        return schedule;
    }

    @Override
    @Transactional
    public AgentSchedule setEnabled(Long userId, Long scheduleId, boolean enabled) {
        AgentSchedule schedule = loadOwned(userId, scheduleId);
        schedule.setEnabled(enabled ? 1 : 0);
        schedule.setLockUntil(null);
        if (enabled) {
            schedule.setConsecutiveFailures(0);
            schedule.setNextRunAt(nextRunAt(schedule.getCronExpression(), schedule.getTimezone(), LocalDateTime.now()));
        }
        scheduleMapper.updateById(schedule);
        return schedule;
    }

    @Override
    public AgentSchedule retryNow(Long userId, Long scheduleId) {
        requireUser(userId);
        return scheduleRunner.executeNow(userId, scheduleId);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long scheduleId) {
        AgentSchedule schedule = loadOwned(userId, scheduleId);
        schedule.setEnabled(0);
        schedule.setNextRunAt(null);
        schedule.setLockUntil(null);
        scheduleMapper.updateById(schedule);
        scheduleMapper.deleteById(scheduleId);
    }

    @Override
    public List<AgentScheduleRun> listRuns(Long userId, Long scheduleId) {
        AgentSchedule schedule = loadOwned(userId, scheduleId);
        return scheduleRunMapper.selectList(new LambdaQueryWrapper<AgentScheduleRun>()
                .eq(AgentScheduleRun::getScheduleId, schedule.getId())
                .eq(AgentScheduleRun::getUserId, userId)
                .orderByDesc(AgentScheduleRun::getStartedAt)
                .last("LIMIT 50"));
    }

    private AgentSchedule loadOwned(Long userId, Long scheduleId) {
        requireUser(userId);
        AgentSchedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null || schedule.getDeleted() != null && schedule.getDeleted() == 1
                || !userId.equals(schedule.getUserId())) {
            throw new IllegalArgumentException("Schedule does not exist");
        }
        return schedule;
    }

    private static LocalDateTime nextRunAt(String cronExpression, String timezone, LocalDateTime from) {
        CronExpression cron = CronExpression.parse(cronExpression);
        ZoneId zoneId = ZoneId.of(cleanTimezone(timezone));
        ZonedDateTime next = cron.next(from.atZone(zoneId));
        if (next == null) {
            throw new IllegalArgumentException("Cron expression has no next execution time");
        }
        return next.toLocalDateTime();
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String cleanTimezone(String timezone) {
        return timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
