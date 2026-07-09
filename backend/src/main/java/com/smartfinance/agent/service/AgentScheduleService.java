package com.smartfinance.agent.service;

import com.smartfinance.agent.entity.AgentSchedule;
import com.smartfinance.agent.entity.AgentScheduleRun;

import java.util.List;

public interface AgentScheduleService {

    List<AgentSchedule> list(Long userId);

    AgentSchedule create(Long userId,
                         String name,
                         String description,
                         String cronExpression,
                         String taskQuery,
                         String timezone);

    AgentSchedule update(Long userId,
                         Long scheduleId,
                         String name,
                         String description,
                         String cronExpression,
                         String taskQuery,
                         String timezone);

    AgentSchedule setEnabled(Long userId, Long scheduleId, boolean enabled);

    AgentSchedule retryNow(Long userId, Long scheduleId);

    List<AgentScheduleRun> listRuns(Long userId, Long scheduleId);

    void delete(Long userId, Long scheduleId);
}
