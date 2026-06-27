package com.smartfinance.agent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.entity.SkillInvocationRecord;
import com.smartfinance.agent.mapper.SkillInvocationRecordMapper;
import com.smartfinance.agent.service.SkillInvocationRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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
        record(userId, traceId, skillName, category, null, null, input, success, false, 0L, summary, rawResult);
    }

    @Override
    public void record(Long userId,
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
                       String rawResult) {
        try {
            SkillInvocationRecord record = new SkillInvocationRecord();
            record.setUserId(userId);
            record.setTraceId(traceId);
            record.setSkillName(skillName);
            record.setCategory(category);
            record.setSourceType(sourceType);
            record.setRiskLevel(riskLevel);
            record.setInput(input == null || input.isNull() ? "{}" : objectMapper.writeValueAsString(input));
            record.setSuccess(success ? 1 : 0);
            record.setBlocked(blocked ? 1 : 0);
            record.setDurationMs(durationMs);
            record.setSummary(truncate(summary, 500));
            record.setRawResult(truncate(rawResult, 2000));
            mapper.insert(record);
        } catch (Exception e) {
            log.warn("Save skill invocation failed: traceId={}, skill={}, error={}",
                    traceId, skillName, e.getMessage());
        }
    }

    @Override
    public List<SkillInvocationRecord> list(Long userId, String skillName, int limit) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        LambdaQueryWrapper<SkillInvocationRecord> query = new LambdaQueryWrapper<SkillInvocationRecord>()
                .eq(SkillInvocationRecord::getUserId, userId)
                .orderByDesc(SkillInvocationRecord::getCreatedAt)
                .last("LIMIT " + safeLimit);
        if (skillName != null && !skillName.isBlank()) {
            query.eq(SkillInvocationRecord::getSkillName, skillName.trim());
        }
        return mapper.selectList(query);
    }

    @Override
    public List<SkillInvocationRecord> listByTraceId(Long userId, String traceId) {
        if (userId == null || traceId == null || traceId.isBlank()) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<SkillInvocationRecord>()
                .eq(SkillInvocationRecord::getUserId, userId)
                .eq(SkillInvocationRecord::getTraceId, traceId.trim())
                .orderByAsc(SkillInvocationRecord::getCreatedAt));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
