package com.smartfinance.agent.service;

import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.dto.AgentMemoryRequest;
import com.smartfinance.agent.dto.CustomSkillDraftRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface PendingActionService {

    PendingAction prepareTransaction(Long userId,
                                     BigDecimal amount,
                                     String type,
                                     String category,
                                     String description,
                                     LocalDate transactionDate);

    PendingAction prepareBudget(Long userId, String category, String month, BigDecimal amount);

    PendingAction prepareCustomSkill(Long userId, CustomSkillDraftRequest request);

    PendingAction prepareCustomSkill(Long userId,
                                     CustomSkillDraftRequest request,
                                     Long sourceReflectionId,
                                     String sourceTraceId);

    PendingAction prepareMemory(Long userId, AgentMemoryRequest request);

    PendingAction prepareMemory(Long userId,
                                AgentMemoryRequest request,
                                Long sourceReflectionId,
                                String sourceTraceId);

    PendingAction prepareSchedule(Long userId,
                                  String name,
                                  String description,
                                  String cronExpression,
                                  String taskQuery,
                                  String timezone);

    PendingAction prepareSchedule(Long userId,
                                  String name,
                                  String description,
                                  String cronExpression,
                                  String taskQuery,
                                  String timezone,
                                  Long sourceReflectionId,
                                  String sourceTraceId);

    List<PendingAction> listPending(Long userId);

    List<PendingAction> list(Long userId, String status);

    PendingAction confirm(Long userId, Long actionId);

    PendingAction cancel(Long userId, Long actionId);
}
