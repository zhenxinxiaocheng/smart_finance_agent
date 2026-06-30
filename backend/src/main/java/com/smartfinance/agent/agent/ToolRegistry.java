package com.smartfinance.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.dto.AgentSkillDefinition;
import com.smartfinance.agent.entity.AgentSkill;
import com.smartfinance.agent.service.AgentSkillService;
import com.smartfinance.agent.service.SkillInvocationRecordService;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;

@Component
public class ToolRegistry {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Map<String, String> TOOL_ALIASES = Map.ofEntries(
            Map.entry("web_search", "search_web"),
            Map.entry("search", "search_web"),
            Map.entry("\u8054\u7f51\u641c\u7d22", "search_web"),
            Map.entry("\u7f51\u7edc\u641c\u7d22", "search_web"),
            Map.entry("\u5b9e\u65f6\u641c\u7d22", "search_web"),
            Map.entry("\u641c\u7d22", "search_web"),
            Map.entry("\u67e5\u8be2\u603b\u652f\u51fa", "get_total_expense"),
            Map.entry("\u603b\u652f\u51fa", "get_total_expense"),
            Map.entry("\u67e5\u8be2\u603b\u6536\u5165", "get_total_income"),
            Map.entry("\u603b\u6536\u5165", "get_total_income"),
            Map.entry("\u652f\u51fa\u5206\u7c7b", "get_expense_by_category"),
            Map.entry("\u5206\u7c7b\u652f\u51fa", "get_expense_by_category"),
            Map.entry("\u6700\u8fd1\u4ea4\u6613", "get_recent_transactions"),
            Map.entry("\u4ea4\u6613\u8bb0\u5f55", "get_recent_transactions"),
            Map.entry("\u6708\u5ea6\u603b\u7ed3", "get_monthly_summary"),
            Map.entry("\u6708\u5ea6\u6458\u8981", "get_monthly_summary"),
            Map.entry("\u8d22\u52a1\u6982\u89c8", "get_finance_overview"),
            Map.entry("\u5e94\u6025\u91d1\u5206\u6790", "analyze_emergency_fund"),
            Map.entry("\u50a8\u84c4\u7387\u8bc4\u4f30", "evaluate_savings_rate"),
            Map.entry("\u5f02\u5e38\u68c0\u6d4b", "detect_anomalies"),
            Map.entry("\u6d88\u8d39\u5bf9\u6807", "compare_with_benchmark"),
            Map.entry("\u9884\u7b97\u89c4\u5212", "budget_planning_wizard"),
            Map.entry("\u7a0e\u52a1\u4f30\u7b97", "tax_estimation"),
            Map.entry("\u5206\u7c7b\u5efa\u8bae", "suggest_category"),
            Map.entry("\u8bb0\u8d26", "record_transaction"),
            Map.entry("\u8bb0\u5f55\u4ea4\u6613", "record_transaction"),
            Map.entry("\u8bbe\u7f6e\u9884\u7b97", "set_budget"),
            Map.entry("\u9884\u7b97\u72b6\u6001", "get_budget_status"),
            Map.entry("\u9884\u7b97\u9884\u8b66", "check_alerts"),
            Map.entry("\u9884\u8b66\u5386\u53f2", "get_alert_history"),
            Map.entry("\u521b\u5efaskill", "create_custom_skill"),
            Map.entry("\u81ea\u5b9a\u4e49skill", "create_custom_skill"),
            Map.entry("\u505a\u6210skill", "create_custom_skill")
    );

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private final SkillInvocationRecordService skillInvocationRecordService;
    private final AgentSkillService agentSkillService;

    public ToolRegistry(FinancialTools financialTools,
                        TransactionRecorder transactionRecorder,
                        WebSearchTool webSearchTool,
                        BudgetTool budgetTool,
                        CustomSkillTool customSkillTool,
                        SkillInvocationRecordService skillInvocationRecordService,
                        AgentSkillService agentSkillService) {
        this.skillInvocationRecordService = skillInvocationRecordService;
        this.agentSkillService = agentSkillService;
        register("get_total_expense", "查询指定日期范围内的总支出。input: {startDate: yyyy-MM-dd, endDate: yyyy-MM-dd}",
                input -> financialTools.getTotalExpense(text(input, "startDate", monthStart()), text(input, "endDate", today())));
        register("get_total_income", "查询指定日期范围内的总收入。input: {startDate: yyyy-MM-dd, endDate: yyyy-MM-dd}",
                input -> financialTools.getTotalIncome(text(input, "startDate", monthStart()), text(input, "endDate", today())));
        register("get_expense_by_category", "按分类查询指定日期范围内的支出明细。input: {startDate, endDate}",
                input -> financialTools.getExpenseByCategory(text(input, "startDate", monthStart()), text(input, "endDate", today())));
        register("get_recent_transactions", "查询指定日期范围内的交易明细。input: {startDate, endDate, limit}",
                input -> financialTools.getRecentTransactions(text(input, "startDate", monthStart()), text(input, "endDate", today()), integer(input, "limit", 10)));
        register("get_monthly_summary", "获取月度财务摘要。input: {year, month}",
                input -> financialTools.getMonthlySummary(integer(input, "year", LocalDate.now().getYear()), integer(input, "month", LocalDate.now().getMonthValue())));
        register("get_finance_overview", "查询日期范围内的财务统计概览。input: {startDate, endDate}",
                input -> financialTools.getFinanceOverview(text(input, "startDate", monthStart()), text(input, "endDate", today())));
        register("analyze_emergency_fund", "分析紧急备用金是否充足。input: {}",
                input -> financialTools.analyzeEmergencyFund());
        register("evaluate_savings_rate", "评估储蓄率健康状况。input: {}",
                input -> financialTools.evaluateSavingsRate());
        register("detect_anomalies", "检测近期异常消费。input: {}",
                input -> financialTools.detectAnomalies());
        register("compare_with_benchmark", "进行消费结构对标分析。input: {}",
                input -> financialTools.compareWithBenchmark());
        register("budget_planning_wizard", "基于历史消费生成预算规划。input: {userBudget}",
                input -> financialTools.budgetPlanningWizard(decimal(input, "userBudget", BigDecimal.ZERO).doubleValue()));
        register("tax_estimation", "估算个人所得税并给出节税建议。input: {}",
                input -> financialTools.taxEstimation());
        register("suggest_category", "根据原始消息和类型推荐交易分类。input: {userMessage, type}",
                input -> transactionRecorder.suggestCategory(text(input, "userMessage", ""), text(input, "type", "EXPENSE")));
        register("record_transaction", "记录一笔收入或支出。input: {userMessage, type, amount, category, description, date}",
                input -> transactionRecorder.recordTransaction(
                        text(input, "userMessage", ""),
                        text(input, "type", "EXPENSE"),
                        decimal(input, "amount", BigDecimal.ZERO),
                        text(input, "category", ""),
                        text(input, "description", ""),
                        text(input, "date", today())));
        register("set_budget", "设置某月某分类或总预算。input: {category, amount, month}",
                input -> budgetTool.setBudget(text(input, "category", "ALL"), decimal(input, "amount", BigDecimal.ZERO), text(input, "month", currentMonth())));
        register("get_budget_status", "查看某月预算执行情况。input: {month}",
                input -> budgetTool.getBudgetStatus(text(input, "month", currentMonth())));
        register("check_alerts", "查看未读预算预警。input: {}",
                input -> budgetTool.checkAlerts());
        register("get_alert_history", "查看近期预算预警历史。input: {limit}",
                input -> budgetTool.getAlertHistory(integer(input, "limit", 5)));
        register("search_web", "搜索实时财经、汇率、市场新闻。input: {query}",
                input -> webSearchTool.searchWeb(text(input, "query", "")));
        register("create_custom_skill", "Create a user-defined Skill draft. input: {name, description, triggerText, instructionText, boundTools, category, riskLevel}",
                input -> customSkillTool.createCustomSkill(
                        text(input, "name", ""),
                        text(input, "description", ""),
                        text(input, "triggerText", ""),
                        text(input, "instructionText", ""),
                        stringList(input, "boundTools"),
                        text(input, "category", "Custom"),
                        text(input, "riskLevel", "READ_ONLY")));
    }

    public String manifest() {
        return manifest(0L);
    }

    public String manifest(Long userId) {
        return agentSkillService.buildEnabledSkillManifest(userId, builtInSkillDefinitions());
    }

    public Collection<AgentSkillDefinition> builtInSkillDefinitions() {
        return tools.entrySet().stream()
                .map(entry -> new AgentSkillDefinition(
                        entry.getKey(),
                        entry.getKey(),
                        entry.getValue().category(),
                        entry.getValue().description(),
                        "1.0.0",
                        "system",
                        entry.getValue().riskLevel(),
                        entry.getValue().inputSchemaHint(),
                        entry.getValue().description(),
                        List.of(entry.getKey())))
                .toList();
    }

    public void syncBuiltInSkills(Long userId) {
        agentSkillService.syncBuiltInSkills(userId, builtInSkillDefinitions());
    }

    private String legacyManifest() {
        StringJoiner joiner = new StringJoiner("\n");
        Set<String> categories = new LinkedHashSet<>();
        tools.values().forEach(tool -> categories.add(tool.category()));
        for (String category : categories) {
            joiner.add("【" + category + "】");
            tools.forEach((name, tool) -> {
                if (category.equals(tool.category())) {
                    joiner.add("- " + name + ": " + tool.description()
                            + " 风险等级: " + tool.riskLevel()
                            + " input: " + tool.inputSchemaHint());
                }
            });
        }
        return joiner.toString();
    }

    public ToolObservation execute(String toolName, JsonNode input, Long userId) {
        return execute(toolName, input, userId, null);
    }

    public ToolObservation execute(String toolName, JsonNode input, Long userId, String traceId) {
        return execute(toolName, input, userId, traceId, null);
    }

    public ToolObservation execute(String toolName, JsonNode input, Long userId, String traceId, String requestedSkillKey) {
        String normalizedToolName = canonicalToolName(toolName);
        ToolDefinition tool = tools.get(normalizedToolName);
        if (tool == null) {
            return ToolObservation.builder()
                    .success(false)
                    .summary("工具不存在：" + toolName)
                    .rawResult("Unknown tool: " + toolName + ". Available tools: " + String.join(", ", tools.keySet()))
                    .build();
        }

        syncBuiltInSkills(userId);
        JsonNode safeInput = input == null || input.isNull() ? MissingNodeHolder.EMPTY : input;
        AgentSkill invocationSkill;
        try {
            invocationSkill = agentSkillService.resolveInvocationSkill(userId, normalizedToolName, requestedSkillKey);
        } catch (Exception e) {
            String skillName = requestedSkillKey == null || requestedSkillKey.isBlank() ? toolName : requestedSkillKey.trim();
            String summary = "Skill rejected: " + e.getMessage();
            skillInvocationRecordService.record(userId, traceId, skillName, tool.category(), "UNKNOWN",
                    tool.riskLevel(), safeInput, false, true, 0L, summary, summary);
            return ToolObservation.builder()
                    .success(false)
                    .summary(summary)
                    .rawResult(summary)
                    .build();
        }
        SkillRuntimeInfo runtimeInfo = runtimeInfo(invocationSkill, normalizedToolName, tool);
        if (!isRuntimeEnabled(invocationSkill)) {
            String summary = "Skill disabled: " + runtimeInfo.skillName();
            skillInvocationRecordService.record(userId, traceId, runtimeInfo.skillName(), runtimeInfo.category(),
                    runtimeInfo.sourceType(), runtimeInfo.riskLevel(), safeInput, false, true, 0L, summary, summary);
            return ToolObservation.builder()
                    .success(false)
                    .summary(summary)
                    .rawResult(summary)
                    .build();
        }

        long started = System.currentTimeMillis();
        try {
            UserIdContext.set(userId);
            String result = tool.executor().apply(safeInput);
            long durationMs = System.currentTimeMillis() - started;
            ToolObservation observation = ToolObservation.builder()
                    .success(true)
                    .summary(compact(result))
                    .rawResult(result)
                    .build();
            skillInvocationRecordService.record(userId, traceId, runtimeInfo.skillName(), runtimeInfo.category(),
                    runtimeInfo.sourceType(), runtimeInfo.riskLevel(), safeInput, true, false, durationMs,
                    observation.getSummary(), result);
            return observation;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - started;
            String summary = "工具执行失败：" + e.getMessage();
            String raw = e.getClass().getSimpleName() + ": " + e.getMessage();
            skillInvocationRecordService.record(userId, traceId, runtimeInfo.skillName(), runtimeInfo.category(),
                    runtimeInfo.sourceType(), runtimeInfo.riskLevel(), safeInput, false, false, durationMs, summary, raw);
            return ToolObservation.builder()
                    .success(false)
                    .summary(summary)
                    .rawResult(raw)
                    .build();
        } finally {
            UserIdContext.clear();
        }
    }

    public String canonicalToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "";
        }
        String trimmed = toolName.trim();
        if (tools.containsKey(trimmed)) {
            return trimmed;
        }
        return TOOL_ALIASES.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
    }

    private SkillRuntimeInfo runtimeInfo(AgentSkill invocationSkill, String toolName, ToolDefinition tool) {
        if (invocationSkill == null) {
            return new SkillRuntimeInfo(toolName, tool.category(), "BUILT_IN", tool.riskLevel());
        }
        return new SkillRuntimeInfo(
                fallback(invocationSkill.getSkillKey(), toolName),
                fallback(invocationSkill.getCategory(), tool.category()),
                fallback(invocationSkill.getSourceType(), "UNKNOWN"),
                fallback(invocationSkill.getRiskLevel(), tool.riskLevel())
        );
    }

    private boolean isRuntimeEnabled(AgentSkill skill) {
        return skill == null || skill.getEnabled() == null || skill.getEnabled() == 1;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void register(String name, String description, Function<JsonNode, String> executor) {
        tools.put(name, new ToolDefinition(description, categoryFor(name), riskFor(name), schemaFor(description), executor));
    }

    private static String categoryFor(String name) {
        if (name.contains("skill")) {
            return "Skill 管理";
        }
        if (name.contains("budget") || name.contains("alert")) {
            return "预算管理";
        }
        if (name.contains("transaction") || name.contains("category")) {
            return "记账辅助";
        }
        if (name.contains("search")) {
            return "联网搜索";
        }
        return "财务查询";
    }

    private static String riskFor(String name) {
        if ("create_custom_skill".equals(name)) {
            return "REQUIRES_CONFIRMATION";
        }
        if ("record_transaction".equals(name) || "set_budget".equals(name)) {
            return "REQUIRES_CONFIRMATION";
        }
        if ("search_web".equals(name)) {
            return "EXTERNAL_INFORMATION";
        }
        return "READ_ONLY";
    }

    private static String schemaFor(String description) {
        int index = description == null ? -1 : description.toLowerCase().indexOf("input:");
        return index < 0 ? "{}" : description.substring(index + "input:".length()).trim();
    }

    private static String text(JsonNode input, String field, String defaultValue) {
        JsonNode node = input.get(field);
        if (node == null || node.isNull()) return defaultValue;
        String value = node.asText();
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int integer(JsonNode input, String field, int defaultValue) {
        JsonNode node = input.get(field);
        return node == null || !node.canConvertToInt() ? defaultValue : node.asInt();
    }

    private static BigDecimal decimal(JsonNode input, String field, BigDecimal defaultValue) {
        JsonNode node = input.get(field);
        if (node == null || node.isNull()) return defaultValue;
        try {
            return new BigDecimal(node.asText());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static List<String> stringList(JsonNode input, String field) {
        JsonNode node = input.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new java.util.ArrayList<>();
            node.forEach(item -> {
                String value = item.asText("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
            return values;
        }
        String value = node.asText("");
        if (value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    private static String monthStart() {
        return LocalDate.now().withDayOfMonth(1).toString();
    }

    private static String currentMonth() {
        return LocalDate.now().format(MONTH_FORMATTER);
    }

    private static String compact(String text) {
        if (text == null || text.isBlank()) return "工具没有返回内容";
        String compacted = text.replaceAll("\\s+", " ").trim();
        return compacted.length() > 160 ? compacted.substring(0, 160) + "..." : compacted;
    }

    private record ToolDefinition(String description,
                                  String category,
                                  String riskLevel,
                                  String inputSchemaHint,
                                  Function<JsonNode, String> executor) {}

    private record SkillRuntimeInfo(String skillName, String category, String sourceType, String riskLevel) {}

    @Data
    @Builder
    public static class ToolObservation {
        private boolean success;
        private String summary;
        private String rawResult;
    }

    private static class MissingNodeHolder {
        private static final JsonNode EMPTY = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
