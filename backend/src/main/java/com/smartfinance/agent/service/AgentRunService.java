package com.smartfinance.agent.service;

import java.util.List;
import java.util.Map;

public interface AgentRunService {

    void startRun(Long userId, String traceId, String query);

    void completeRun(String traceId, String finalAnswer);

    void failRun(String traceId, String errorMessage, String finalAnswer);

    void recordStepStarted(Long userId, String traceId, int stepNumber, String summary, String toolName);

    void recordStepFinished(Long userId,
                            String traceId,
                            int stepNumber,
                            String summary,
                            String toolName,
                            String input,
                            boolean success,
                            String observationSummary,
                            String errorMessage);

    Map<String, List<Map<String, Object>>> stepsByTraceIds(List<String> traceIds);
}
