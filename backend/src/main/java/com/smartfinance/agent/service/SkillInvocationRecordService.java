package com.smartfinance.agent.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface SkillInvocationRecordService {

    void record(Long userId,
                String traceId,
                String skillName,
                String category,
                JsonNode input,
                boolean success,
                String summary,
                String rawResult);
}
