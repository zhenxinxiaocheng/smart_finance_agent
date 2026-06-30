package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.dto.CustomSkillDraftRequest;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.mapper.PendingActionMapper;
import com.smartfinance.agent.service.AgentSkillService;
import com.smartfinance.agent.service.BudgetService;
import com.smartfinance.agent.service.PendingActionService;
import com.smartfinance.agent.service.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PendingActionServiceImpl implements PendingActionService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final PendingActionMapper pendingActionMapper;
    private final TransactionService transactionService;
    private final BudgetService budgetService;
    private final AgentSkillService agentSkillService;
    private final ObjectMapper objectMapper;

    public PendingActionServiceImpl(PendingActionMapper pendingActionMapper,
                                    TransactionService transactionService,
                                    BudgetService budgetService,
                                    AgentSkillService agentSkillService,
                                    ObjectMapper objectMapper) {
        this.pendingActionMapper = pendingActionMapper;
        this.transactionService = transactionService;
        this.budgetService = budgetService;
        this.agentSkillService = agentSkillService;
        this.objectMapper = objectMapper;
    }

    @Override
    public PendingAction prepareTransaction(Long userId,
                                            BigDecimal amount,
                                            String type,
                                            String category,
                                            String description,
                                            LocalDate transactionDate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", amount == null ? "0" : amount.toPlainString());
        payload.put("type", type);
        payload.put("category", category);
        payload.put("description", description);
        payload.put("transactionDate", transactionDate == null ? LocalDate.now().toString() : transactionDate.toString());

        String typeLabel = "INCOME".equals(type) ? "收入" : "支出";
        PendingAction action = new PendingAction();
        action.setUserId(userId);
        action.setActionType("RECORD_TRANSACTION");
        action.setTitle("确认记账");
        action.setSummary("%s %s 元 · %s · %s".formatted(typeLabel, amount, category, defaultText(description, "无备注")));
        action.setPayload(toJson(payload));
        action.setStatus(STATUS_PENDING);
        pendingActionMapper.insert(action);
        return action;
    }

    @Override
    public PendingAction prepareBudget(Long userId, String category, String month, BigDecimal amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", category);
        payload.put("month", month);
        payload.put("amount", amount == null ? "0" : amount.toPlainString());

        PendingAction action = new PendingAction();
        action.setUserId(userId);
        action.setActionType("SET_BUDGET");
        action.setTitle("确认设置预算");
        action.setSummary("%s · %s · %s 元".formatted(month, category, amount));
        action.setPayload(toJson(payload));
        action.setStatus(STATUS_PENDING);
        pendingActionMapper.insert(action);
        return action;
    }

    @Override
    public PendingAction prepareCustomSkill(Long userId, CustomSkillDraftRequest request) {
        Map<String, Object> payload = objectMapper.convertValue(request, new TypeReference<>() {});
        PendingAction action = new PendingAction();
        action.setUserId(userId);
        action.setActionType("INSTALL_CUSTOM_SKILL");
        action.setTitle("确认安装自定义 Skill");
        action.setSummary("%s · %s".formatted(
                defaultText(request.getName(), "Custom Skill"),
                defaultText(request.getDescription(), "用户通过对话创建的 Skill")));
        action.setPayload(toJson(payload));
        action.setStatus(STATUS_PENDING);
        pendingActionMapper.insert(action);
        return action;
    }

    @Override
    public List<PendingAction> listPending(Long userId) {
        return pendingActionMapper.selectList(new LambdaQueryWrapper<PendingAction>()
                .eq(PendingAction::getUserId, userId)
                .eq(PendingAction::getStatus, STATUS_PENDING)
                .orderByDesc(PendingAction::getCreatedAt));
    }

    @Override
    @Transactional
    public PendingAction confirm(Long userId, Long actionId) {
        PendingAction action = loadPendingAction(userId, actionId);
        Map<String, Object> payload = readPayload(action.getPayload());
        if ("INSTALL_CUSTOM_SKILL".equals(action.getActionType())) {
            CustomSkillDraftRequest request = objectMapper.convertValue(payload, CustomSkillDraftRequest.class);
            agentSkillService.installCustomSkill(userId, request);
            action.setStatus(STATUS_CONFIRMED);
            pendingActionMapper.updateById(action);
            return action;
        }
        if ("RECORD_TRANSACTION".equals(action.getActionType())) {
            transactionService.add(
                    userId,
                    new BigDecimal(text(payload, "amount")),
                    text(payload, "type"),
                    text(payload, "category"),
                    text(payload, "description"),
                    LocalDate.parse(text(payload, "transactionDate"))
            );
        } else if ("SET_BUDGET".equals(action.getActionType())) {
            budgetService.setBudget(
                    userId,
                    text(payload, "category"),
                    text(payload, "month"),
                    new BigDecimal(text(payload, "amount")),
                    null
            );
        } else {
            throw new IllegalArgumentException("不支持的待确认操作：" + action.getActionType());
        }
        action.setStatus(STATUS_CONFIRMED);
        pendingActionMapper.updateById(action);
        return action;
    }

    @Override
    @Transactional
    public PendingAction cancel(Long userId, Long actionId) {
        PendingAction action = loadPendingAction(userId, actionId);
        action.setStatus(STATUS_CANCELLED);
        pendingActionMapper.updateById(action);
        return action;
    }

    private PendingAction loadPendingAction(Long userId, Long actionId) {
        PendingAction action = pendingActionMapper.selectById(actionId);
        if (action == null || !userId.equals(action.getUserId())) {
            throw new IllegalArgumentException("待确认操作不存在");
        }
        if (!STATUS_PENDING.equals(action.getStatus())) {
            throw new IllegalArgumentException("该操作已处理");
        }
        return action;
    }

    private Map<String, Object> readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("待确认操作内容解析失败", e);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("待确认操作内容生成失败", e);
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
