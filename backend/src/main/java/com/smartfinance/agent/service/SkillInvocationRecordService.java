package com.smartfinance.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfinance.agent.entity.SkillInvocationRecord;

import java.util.List;

public interface SkillInvocationRecordService {

    void record(Long userId,
                String traceId,
                String skillName,
                String category,
                JsonNode input,
                boolean success,
                String summary,
                String rawResult);

    void record(Long userId,
                String traceId,
                String skillName,
                String category,
                String sourceType,
                String riskLevel,
                JsonNode input,
                boolean success,
                boolean blocked,
                long durationMs,
                String summary,
                String rawResult);

    List<SkillInvocationRecord> list(Long userId, String skillName, int limit);

    List<SkillInvocationRecord> listByTraceId(Long userId, String traceId);
}
