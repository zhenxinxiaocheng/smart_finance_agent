package com.smartfinance.agent.agent;

import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.dto.CustomSkillDraftRequest;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.service.PendingActionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class CustomSkillTool {

    private static final Set<String> SAFE_BOUND_TOOLS = Set.of(
            "get_total_expense",
            "get_total_income",
            "get_expense_by_category",
            "get_recent_transactions",
            "get_monthly_summary",
            "get_finance_overview",
            "analyze_emergency_fund",
            "evaluate_savings_rate",
            "detect_anomalies",
            "compare_with_benchmark",
            "budget_planning_wizard",
            "tax_estimation",
            "suggest_category",
            "record_transaction",
            "set_budget",
            "get_budget_status",
            "check_alerts",
            "get_alert_history",
            "search_web"
    );

    private final PendingActionService pendingActionService;

    public CustomSkillTool(PendingActionService pendingActionService) {
        this.pendingActionService = pendingActionService;
    }

    public String createCustomSkill(String name,
                                    String description,
                                    String triggerText,
                                    String instructionText,
                                    List<String> boundTools,
                                    String category,
                                    String riskLevel) {
        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }
        CustomSkillDraftRequest request = new CustomSkillDraftRequest();
        request.setName(defaultText(name, "Custom Skill"));
        request.setDescription(defaultText(description, "用户通过对话创建的自定义 Skill"));
        request.setTriggerText(defaultText(triggerText, request.getDescription()));
        request.setInstructionText(defaultText(instructionText, request.getDescription()));
        request.setBoundTools(normalizeBoundTools(boundTools));
        request.setCategory(defaultText(category, "Custom"));
        request.setRiskLevel(defaultText(riskLevel, riskFromTools(request.getBoundTools())));
        PendingAction action = pendingActionService.prepareCustomSkill(userId, request);
        return "已生成自定义 Skill 安装确认，请用户确认后生效：%s。待确认ID：%d"
                .formatted(request.getName(), action.getId());
    }

    private List<String> normalizeBoundTools(List<String> boundTools) {
        if (boundTools == null || boundTools.isEmpty()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String tool : boundTools) {
            String normalized = normalizeTool(tool);
            if (SAFE_BOUND_TOOLS.contains(normalized)) {
                result.add(normalized);
            }
        }
        return new ArrayList<>(result);
    }

    private String normalizeTool(String tool) {
        if (tool == null || tool.isBlank()) {
            return "";
        }
        String value = tool.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "web_search", "search", "联网搜索", "网络搜索", "实时搜索", "搜索" -> "search_web";
            case "记账", "记录交易" -> "record_transaction";
            case "设置预算" -> "set_budget";
            default -> lower;
        };
    }

    private String riskFromTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return "READ_ONLY";
        }
        if (tools.contains("record_transaction") || tools.contains("set_budget")) {
            return "REQUIRES_CONFIRMATION";
        }
        if (tools.contains("search_web")) {
            return "EXTERNAL_INFORMATION";
        }
        return "READ_ONLY";
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
