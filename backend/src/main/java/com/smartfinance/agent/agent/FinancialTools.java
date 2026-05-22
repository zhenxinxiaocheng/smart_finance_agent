package com.smartfinance.agent.agent;

import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.mapper.TransactionMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class FinancialTools {

    private final TransactionMapper transactionMapper;

    public FinancialTools(TransactionMapper transactionMapper) {
        this.transactionMapper = transactionMapper;
    }

    @Tool("查询用户在指定时间范围内的总支出")
    public String getTotalExpense(@P("开始日期(yyyy-MM-dd)") String startDate,
                                  @P("结束日期(yyyy-MM-dd)") String endDate) {
        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        BigDecimal total = transactionMapper.sumByUserAndTypeAndDateRange(userId, "EXPENSE", start, end);
        String result = String.format("在 %s 至 %s 期间，总支出为 %.2f 元", startDate, endDate, total);
        log.info("Tool[getTotalExpense] userId={}, result={}", userId, result);
        return result;
    }

    @Tool("查询用户在指定时间范围内的总收入")
    public String getTotalIncome(@P("开始日期(yyyy-MM-dd)") String startDate,
                                 @P("结束日期(yyyy-MM-dd)") String endDate) {
        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        BigDecimal total = transactionMapper.sumByUserAndTypeAndDateRange(userId, "INCOME", start, end);
        String result = String.format("在 %s 至 %s 期间，总收入为 %.2f 元", startDate, endDate, total);
        log.info("Tool[getTotalIncome] userId={}, result={}", userId, result);
        return result;
    }

    @Tool("查询用户在指定时间范围内按消费分类的支出明细，返回各分类的消费金额和占比")
    public String getExpenseByCategory(@P("开始日期(yyyy-MM-dd)") String startDate,
                                       @P("结束日期(yyyy-MM-dd)") String endDate) {
        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        List<Map<String, Object>> categoryData = transactionMapper.sumByCategory(userId, start, end);

        if (categoryData.isEmpty()) {
            return "该时间范围内暂无支出记录";
        }

        BigDecimal total = categoryData.stream()
                .map(m -> (BigDecimal) m.get("total"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String detail = categoryData.stream()
                .map(m -> {
                    String category = (String) m.get("category");
                    BigDecimal amount = (BigDecimal) m.get("total");
                    double pct = amount.divide(total, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(1, RoundingMode.HALF_UP)
                            .doubleValue();
                    return String.format("  - %s: %.2f 元 (占比 %.1f%%)", category, amount, pct);
                })
                .collect(Collectors.joining("\n"));

        return String.format("在 %s 至 %s 期间，总支出 %.2f 元，按分类明细如下：\n%s",
                startDate, endDate, total, detail);
    }

    @Tool("查询用户最近的交易记录明细")
    public String getRecentTransactions(@P("开始日期(yyyy-MM-dd)") String startDate,
                                        @P("结束日期(yyyy-MM-dd)") String endDate,
                                        @P("返回的记录条数上限") int limit) {
        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        List<Transaction> list = transactionMapper.selectByUserAndDateRange(userId, start, end);

        if (list.isEmpty()) {
            return "该时间范围内暂无交易记录";
        }

        String records = list.stream()
                .limit(Math.min(limit, list.size()))
                .map(t -> String.format("  [%s] %s | %s | %.2f | %s",
                        t.getTransactionDate(),
                        t.getType().equals("EXPENSE") ? "支出" : "收入",
                        t.getCategory(),
                        t.getAmount(),
                        t.getDescription() != null ? t.getDescription() : ""))
                .collect(Collectors.joining("\n"));

        return String.format("在 %s 至 %s 期间，共 %d 条记录，最近记录如下：\n%s",
                startDate, endDate, list.size(), records);
    }

    @Tool("获取用户的月度财务摘要，包含收入、支出、结余和主要消费分类")
    public String getMonthlySummary(@P("年份(如2026)") int year,
                                    @P("月份(1-12)") int month) {
        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }
        String monthStr = String.format("%d-%02d", year, month);
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

        BigDecimal income = transactionMapper.sumByUserAndTypeAndDateRange(userId, "INCOME", start, end);
        BigDecimal expense = transactionMapper.sumByUserAndTypeAndDateRange(userId, "EXPENSE", start, end);
        BigDecimal balance = income.subtract(expense);

        List<Map<String, Object>> categoryData = transactionMapper.sumByCategory(userId, start, end);

        String topCategories = categoryData.stream()
                .limit(3)
                .map(m -> String.format("  - %s: %.2f 元", m.get("category"), m.get("total")))
                .collect(Collectors.joining("\n"));

        String suggestion;
        double savingsRate = income.compareTo(BigDecimal.ZERO) > 0
                ? balance.divide(income, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue()
                : 0;

        if (savingsRate < 10) {
            suggestion = "储蓄率偏低（" + String.format("%.1f%%", savingsRate) + "），建议适当控制非必要支出";
        } else if (savingsRate < 30) {
            suggestion = "储蓄率良好（" + String.format("%.1f%%", savingsRate) + "），继续保持";
        } else {
            suggestion = "储蓄率优秀（" + String.format("%.1f%%", savingsRate) + "），财务状况健康";
        }

        return String.format("""
                %s 月度财务摘要
                ─────────────────────
                总收入：%.2f 元
                总支出：%.2f 元
                结余：%.2f 元
                储蓄率：%.1f%%

                支出分类TOP3：
                %s

                建议：%s
                """, monthStr, income, expense, balance, savingsRate, topCategories, suggestion);
    }

    @Tool("按日期范围查询用户的财务统计概览数据（总收入、总支出、结余、支出分类数），用于生成图表")
    public String getFinanceOverview(@P("开始日期(yyyy-MM-dd)") String startDate,
                                     @P("结束日期(yyyy-MM-dd)") String endDate) {
        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        BigDecimal income = transactionMapper.sumByUserAndTypeAndDateRange(userId, "INCOME", start, end);
        BigDecimal expense = transactionMapper.sumByUserAndTypeAndDateRange(userId, "EXPENSE", start, end);
        List<Map<String, Object>> categoryData = transactionMapper.sumByCategory(userId, start, end);

        return String.format("""
                财务概览数据：
                总收入：%.2f 元
                总支出：%.2f 元
                结余：%.2f 元
                支出分类数：%d
                """, income, expense, income.subtract(expense), categoryData.size());
    }

    @Tool("根据用户的月度支出情况，计算建议的紧急备用金金额范围")
    public String analyzeEmergencyFund() {
        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(3);
        BigDecimal totalExpense = transactionMapper.sumByUserAndTypeAndDateRange(userId, "EXPENSE", start, end);

        if (totalExpense.compareTo(BigDecimal.ZERO) == 0) {
            return "暂无足够的支出数据来分析紧急备用金情况，请先录入消费记录";
        }

        BigDecimal avgMonthlyExpense = totalExpense.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        BigDecimal recommended3Months = avgMonthlyExpense.multiply(BigDecimal.valueOf(3));
        BigDecimal recommended6Months = avgMonthlyExpense.multiply(BigDecimal.valueOf(6));

        return String.format("""
                紧急备用金建议
                ─────────────────
                近3个月平均月支出：%.2f 元
                建议备用金范围：%.2f ~ %.2f 元
                （基础建议3个月，推荐储备6个月）

                请根据你的实际存款情况，对照以上建议范围进行储备。
                紧急备用金应存放在随时可取的活期账户或货币基金中。
                """, avgMonthlyExpense, recommended3Months, recommended6Months);
    }

    @Tool("评估用户的储蓄率健康状况，提供专业的储蓄建议")
    public String evaluateSavingsRate() {
        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }
        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);

        BigDecimal income = transactionMapper.sumByUserAndTypeAndDateRange(userId, "INCOME", monthStart, now);
        BigDecimal expense = transactionMapper.sumByUserAndTypeAndDateRange(userId, "EXPENSE", monthStart, now);

        if (income.compareTo(BigDecimal.ZERO) == 0) {
            return "本月暂无收入数据，无法评估储蓄率";
        }

        BigDecimal balance = income.subtract(expense);
        double savingsRate = balance.divide(income, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        String grade;
        String suggestion;
        String emoji;

        if (savingsRate >= 50) {
            grade = "卓越";
            suggestion = "储蓄率非常优秀！建议考虑投资增值，如指数基金、债券等";
            emoji = "\uD83E\uDD47";
        } else if (savingsRate >= 30) {
            grade = "优秀";
            suggestion = "储蓄率良好！可适当增加投资配置，实现资产增值";
            emoji = "\uD83D\uDC51";
        } else if (savingsRate >= 20) {
            grade = "良好";
            suggestion = "储蓄率处于健康水平，继续保持，可考虑增加应急储备";
            emoji = "\uD83D\uDFE2";
        } else if (savingsRate >= 10) {
            grade = "一般";
            suggestion = "储蓄率偏低，建议优化支出结构，设定每月储蓄目标";
            emoji = "\uD83D\uDFE1";
        } else {
            grade = "需改善";
            suggestion = "储蓄率较低，建议制定详细预算，优先保障储蓄";
            emoji = "\uD83D\uDD34";
        }

        return String.format("""
                储蓄率评估
                ──────────
                本月收入：%.2f 元
                本月支出：%.2f 元
                本月结余：%.2f 元
                储蓄率：%.1f%%
                评级：%s %s

                建议：%s
                """, income, expense, balance, savingsRate, grade, emoji, suggestion);
    }

    @Tool("根据用户的财务状况，提供专业的资产配置建议")
    public String getInvestmentAdvice(@P("用户年龄") int age,
                                      @P("风险承受能力：保守/稳健/积极/激进") String riskProfile) {
        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }
        LocalDate now = LocalDate.now();
        LocalDate yearStart = now.withDayOfYear(1);

        BigDecimal totalIncome = transactionMapper.sumByUserAndTypeAndDateRange(userId, "INCOME", yearStart, now);
        BigDecimal totalExpense = transactionMapper.sumByUserAndTypeAndDateRange(userId, "EXPENSE", yearStart, now);
        BigDecimal annualBalance = totalIncome.subtract(totalExpense);

        int equityRatio;
        int bondRatio;
        int cashRatio;

        switch (riskProfile) {
            case "保守" -> {
                equityRatio = 20; bondRatio = 50; cashRatio = 30;
            }
            case "稳健" -> {
                equityRatio = 40; bondRatio = 40; cashRatio = 20;
            }
            case "积极" -> {
                equityRatio = 60; bondRatio = 30; cashRatio = 10;
            }
            case "激进" -> {
                equityRatio = 80; bondRatio = 15; cashRatio = 5;
            }
            default -> {
                equityRatio = 40; bondRatio = 40; cashRatio = 20;
            }
        }

        if (age < 30) {
            equityRatio += 10; cashRatio -= 10;
        } else if (age > 55) {
            equityRatio -= 15; bondRatio += 10; cashRatio += 5;
        }

        String allocationAdvice = String.format("推荐资产配置：\n" +
                "  - 权益类（股票/指数基金）：%d%%\n" +
                "  - 固定收益（债券/理财）：%d%%\n" +
                "  - 现金及等价物：%d%%",
                equityRatio, bondRatio, cashRatio);

        return String.format("""
                资产配置建议
                ────────────
                用户年龄：%d岁
                风险偏好：%s
                年度结余：%.2f 元

                %s

                投资建议：
                - 先确保3-6个月紧急备用金充足
                - 建议采用定期定额投资方式
                - 选择低成本指数基金作为核心配置
                - 定期（如每半年）进行资产再平衡
                - 投资有风险，建议仔细评估后决策
                """, age, riskProfile, annualBalance, allocationAdvice);
    }
}
