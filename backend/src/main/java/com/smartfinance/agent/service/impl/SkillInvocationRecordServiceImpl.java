package com.smartfinance.agent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.entity.SkillInvocationRecord;
import com.smartfinance.agent.mapper.SkillInvocationRecordMapper;
import com.smartfinance.agent.service.SkillInvocationRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SkillInvocationRecordServiceImpl implements SkillInvocationRecordService {

    private final SkillInvocationRecordMapper mapper;
    private final ObjectMapper objectMapper;

    public SkillInvocationRecordServiceImpl(SkillInvocationRecordMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(Long userId,
                       String traceId,
                       String skillName,
                       String category,
                       JsonNode input,
                       boolean success,
                       String summary,
                       String rawResult) {
        try {
            SkillInvocationRecord record = new SkillInvocationRecord();
            record.setUserId(userId);
            record.setTraceId(traceId);
            record.setSkillName(skillName);
            record.setCategory(category);
            record.setInput(input == null || input.isNull() ? "{}" : objectMapper.writeValueAsString(input));
            record.setSuccess(success ? 1 : 0);
            record.setSummary(truncate(summary, 500));
            record.setRawResult(truncate(rawResult, 2000));
            mapper.insert(record);
        } catch (Exception e) {
            log.warn("Save skill invocation failed: traceId={}, skill={}, error={}",
                    traceId, skillName, e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
