package com.smartfinance.agent.agent;

import com.smartfinance.agent.dto.AgentSkillDefinition;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class ToolSelectionService {

    private final ToolRegistry toolRegistry;

    public ToolSelectionService(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public String manifest(Long userId, String userMessage) {
        Collection<AgentSkillDefinition> definitions = toolRegistry.builtInSkillDefinitions();
        if (definitions == null || definitions.isEmpty()) {
            return toolRegistry.manifest(userId);
        }
        Set<String> selected = selectToolKeys(userMessage);
        Collection<AgentSkillDefinition> filtered = definitions.stream()
                .filter(definition -> selected.contains(definition.skillKey()))
                .toList();
        if (filtered.isEmpty()) {
            filtered = definitions.stream()
                    .filter(definition -> "get_finance_overview".equals(definition.skillKey())
                            || "get_recent_transactions".equals(definition.skillKey()))
                    .toList();
        }
        return toolRegistry.manifest(userId, filtered);
    }

    private Set<String> selectToolKeys(String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim().toLowerCase(Locale.ROOT);
        Set<String> keys = new LinkedHashSet<>();

        if (containsAny(text, "新闻", "实时", "行情", "纳指", "股票", "基金", "汇率", "政策", "上市公司")) {
            keys.add("search_web");
        }
        if (containsAny(text, "定期", "每天", "每周", "每月", "提醒我", "自动帮我", "周期")) {
            keys.add("create_agent_schedule");
        }
        if (containsAny(text, "skill", "技能", "做成", "以后遇到", "流程")) {
            keys.add("create_custom_skill");
        }
        if (containsAny(text, "记账", "记一笔", "记录一笔", "补记", "花了", "收入了")) {
            keys.add("record_transaction");
            keys.add("suggest_category");
        }
        if (containsAny(text, "预算", "超支", "预警")) {
            keys.add("set_budget");
            keys.add("get_budget_status");
            keys.add("check_alerts");
            keys.add("get_alert_history");
            keys.add("budget_planning_wizard");
        }
        if (keys.isEmpty() || containsAny(text, "消费", "支出", "收入", "分类", "省钱", "财务", "月", "餐饮")) {
            keys.add("get_total_expense");
            keys.add("get_total_income");
            keys.add("get_expense_by_category");
            keys.add("get_recent_transactions");
            keys.add("get_monthly_summary");
            keys.add("get_finance_overview");
            keys.add("analyze_emergency_fund");
            keys.add("evaluate_savings_rate");
            keys.add("detect_anomalies");
            keys.add("compare_with_benchmark");
            keys.add("tax_estimation");
        }
        return keys;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
