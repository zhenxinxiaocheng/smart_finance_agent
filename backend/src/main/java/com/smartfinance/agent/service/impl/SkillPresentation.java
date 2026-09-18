package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.entity.AgentSkill;
import java.util.Map;
import static java.util.Map.entry;

/** Product copy is intentionally separate from model instructions and schemas. */
final class SkillPresentation {
    private record Copy(String name, String description, String example) {}
    private static final Map<String, Copy> BUILT_INS = Map.ofEntries(
        entry("record_transaction", new Copy("记一笔", "记录收入或支出，由你确认后入账。", "我中午吃了50元")),
        entry("suggest_category", new Copy("推荐账单分类", "为一笔收支推荐合适的分类。", "买咖啡应该归到哪个分类？")),
        entry("get_total_expense", new Copy("查看总支出", "查看一段时间内花了多少钱。", "我这个月花了多少钱？")),
        entry("get_total_income", new Copy("查看总收入", "查看一段时间内的收入。", "我这个月收入多少？")),
        entry("get_expense_by_category", new Copy("查看分类支出", "了解各类消费分别花了多少。", "这个月餐饮花了多少？")),
        entry("get_recent_transactions", new Copy("查看账单", "查找最近或指定日期的收支记录。", "看看我最近的十笔账单")),
        entry("get_monthly_summary", new Copy("月度收支总结", "汇总一个月的收入和支出。", "总结一下上个月的收支")),
        entry("get_finance_overview", new Copy("财务概览", "查看一段时间的收支状况。", "最近一个月财务状况怎么样？")),
        entry("analyze_emergency_fund", new Copy("应急储备分析", "结合财务信息评估应急储备。", "我的应急储备够用吗？")),
        entry("evaluate_savings_rate", new Copy("储蓄情况分析", "查看收入中有多少能够存下来。", "我的储蓄率怎么样？")),
        entry("detect_anomalies", new Copy("异常消费检查", "查找近期值得留意的消费记录。", "最近有没有异常消费？")),
        entry("compare_with_benchmark", new Copy("消费结构分析", "分析各类消费的占比。", "我的消费结构合理吗？")),
        entry("budget_planning_wizard", new Copy("规划预算", "参考历史消费制定预算建议。", "帮我规划下个月的预算")),
        entry("tax_estimation", new Copy("估算个税", "根据已有收入记录提供个税估算。", "帮我估算今年的个税")),
        entry("set_budget", new Copy("设置预算", "设置月度预算，由你确认后生效。", "把这个月餐饮预算设为800元")),
        entry("get_budget_status", new Copy("查看预算进度", "查看预算已使用多少、还剩多少。", "这个月预算还剩多少？")),
        entry("check_alerts", new Copy("检查预算提醒", "查看当前需要关注的预算情况。", "有没有超预算的分类？")),
        entry("get_alert_history", new Copy("查看提醒记录", "回顾过去的预算提醒。", "看看最近的预算提醒")),
        entry("search_web", new Copy("联网查询", "查询近期财经新闻和市场信息。", "今天有哪些财经新闻？")),
        entry("create_agent_schedule", new Copy("设置定期提醒", "安排定期检查或复盘，由你确认后启用。", "每周日晚上八点提醒我复盘消费")),
        entry("create_custom_skill", new Copy("创建自定义技能", "将常用的处理流程保存为技能。", "把每月收支复盘做成一个技能")),
        entry("get_investment_overview", new Copy("投资概览", "查看投资资产、投入和盈亏。", "看看我的投资总体情况")),
        entry("get_investment_positions", new Copy("查看持仓", "查看持仓、成本和市值。", "我现在持有哪些资产？")),
        entry("get_investment_analysis", new Copy("投资组合分析", "查看已有的组合分析和风险提示。", "分析一下我的投资组合风险")),
        entry("get_investment_data_quality", new Copy("检查投资数据", "检查投资数据是否完整、及时。", "我的投资数据有没有缺失？")),
        entry("get_investment_recommendations", new Copy("查看投资提示", "查看基于已有数据生成的风险提示。", "我的持仓有哪些需要注意的风险？"))
    );

    static AgentSkill apply(AgentSkill skill) {
        Copy copy = Integer.valueOf(1).equals(skill.getBuiltIn()) ? BUILT_INS.get(skill.getSkillKey()) : null;
        skill.setDisplayName(copy == null ? skill.getName() : copy.name());
        skill.setUserDescription(copy == null ? skill.getDescription() : copy.description());
        skill.setExample(copy == null ? skill.getTriggerText() : copy.example());
        return skill;
    }
}
