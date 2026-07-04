package com.smartfinance.agent.service;

import com.smartfinance.agent.entity.AgentReflection;
import com.smartfinance.agent.dto.AgentReflectionAcceptRequest;

import java.util.List;

public interface AgentReflectionService {

    List<AgentReflection> reflectRun(Long userId, String traceId);

    AgentReflection reflectScheduleFailure(Long userId,
                                           Long scheduleId,
                                           String taskQuery,
                                           String errorMessage,
                                           Integer consecutiveFailures);

    List<AgentReflection> list(Long userId, String status, String suggestionType);

    default AgentReflection accept(Long userId, Long reflectionId) {
        return accept(userId, reflectionId, null);
    }

    AgentReflection accept(Long userId, Long reflectionId, AgentReflectionAcceptRequest request);

    AgentReflection dismiss(Long userId, Long reflectionId);
}
