package com.smartfinance.agent.service;

import com.smartfinance.agent.entity.PendingAction;
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

    List<PendingAction> listPending(Long userId);

    PendingAction confirm(Long userId, Long actionId);

    PendingAction cancel(Long userId, Long actionId);
}
