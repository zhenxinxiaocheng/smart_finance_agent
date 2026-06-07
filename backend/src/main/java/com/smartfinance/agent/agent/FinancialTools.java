package com.smartfinance.agent.agent;

import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.entity.ExpenseCategory;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.mapper.ExpenseCategoryMapper;
import com.smartfinance.agent.mapper.TransactionMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class FinancialTools {

    private final TransactionMapper transactionMapper;
    private final ExpenseCategoryMapper expenseCategoryMapper;

    public FinancialTools(TransactionMapper transactionMapper,
                          ExpenseCategoryMapper expenseCategoryMapper) {
        this.transactionMapper = transactionMapper;
        this.expenseCategoryMapper = expenseCategoryMapper;
    }

    @Tool("查询用户在指定时间范围内的总支出")
    public String getTotalExpense(@P("开始日期(yyyy-MM-dd)") String startDate,
                                  @P("结束日期(yyyy-MM-dd)") String endDate) {
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
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
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
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
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
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
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
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
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
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
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
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
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
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
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
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
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
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

    @Tool("检测用户近期的异常消费行为，包括单日支出异常、高额单笔消费、分类支出突增等")
    public String detectAnomalies() {
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
        }
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(89);
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(89);
        long windowDays = ChronoUnit.DAYS.between(start, end) + 1;

        List<Map<String, Object>> dailyExpenses = transactionMapper.sumDailyExpense(userId, start, end);
        if (dailyExpenses.isEmpty()) {
            return "近90天暂无支出数据，无法进行异常检测";
        }

        BigDecimal totalExpense = BigDecimal.ZERO;
        for (Map<String, Object> row : dailyExpenses) {
            totalExpense = totalExpense.add((BigDecimal) row.get("total"));
        }
        BigDecimal avgDailyExpense = totalExpense.divide(BigDecimal.valueOf(windowDays),
                2, RoundingMode.HALF_UP);
        BigDecimal anomalyThreshold = avgDailyExpense.multiply(BigDecimal.valueOf(2));

        StringBuilder anomalies = new StringBuilder();
        anomalies.append("消费异常检测报告\n");
        anomalies.append("─────────────────\n");
        anomalies.append(String.format("近90天日均消费：%.2f 元\n\n", avgDailyExpense));

        List<String> anomalyDays = dailyExpenses.stream()
                .filter(row -> ((BigDecimal) row.get("total")).compareTo(anomalyThreshold) > 0)
                .map(row -> String.format("  %s: %.2f 元 (超出日均 %.1f%%)",
                        row.get("date"),
                        (BigDecimal) row.get("total"),
                        ((BigDecimal) row.get("total")).divide(avgDailyExpense, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100)).doubleValue() - 100))
                .toList();

        if (!anomalyDays.isEmpty()) {
            anomalies.append("⚠️ 异常高消费日：\n");
            anomalyDays.stream().limit(5).forEach(d -> anomalies.append(d).append("\n"));
            anomalies.append("\n");
        }

        BigDecimal highAmountThreshold = avgDailyExpense.multiply(BigDecimal.valueOf(3));
        List<Transaction> highTransactions = transactionMapper.selectExpensesAboveAmount(
                userId, highAmountThreshold, start, end);
        if (!highTransactions.isEmpty()) {
            anomalies.append("⚠️ 高额单笔消费：\n");
            highTransactions.stream().limit(5).forEach(t ->
                    anomalies.append(String.format("  [%s] %s %.2f 元 — %s\n",
                            t.getTransactionDate(), t.getCategory(), t.getAmount(),
                            t.getDescription() != null ? t.getDescription() : "")));
            anomalies.append("\n");
        }

        List<Map<String, Object>> currentCategories = transactionMapper.sumByCategory(userId, start, end);
        List<Map<String, Object>> prevCategories = transactionMapper.sumByCategory(userId, prevStart, prevEnd);
        if (!currentCategories.isEmpty() && !prevCategories.isEmpty()) {
            Map<String, BigDecimal> prevMap = prevCategories.stream()
                    .collect(Collectors.toMap(m -> (String) m.get("category"), m -> (BigDecimal) m.get("total")));
            anomalies.append("📊 分类支出变化（对比上季度）：\n");
            currentCategories.stream().limit(5).forEach(m -> {
                String cat = (String) m.get("category");
                BigDecimal cur = (BigDecimal) m.get("total");
                BigDecimal prev = prevMap.getOrDefault(cat, BigDecimal.ZERO);
                if (prev.compareTo(BigDecimal.ZERO) > 0) {
                    double change = cur.divide(prev, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue() - 100;
                    String arrow = change > 20 ? " 🔴↑" : change > 0 ? " 🟡↑" : " 🟢↓";
                    anomalies.append(String.format("  %s: %.2f → %.2f (%.1f%%)%s\n",
                            cat, prev, cur, change, arrow));
                }
            });
        }

        if (anomalyDays.isEmpty() && highTransactions.isEmpty()
                && (currentCategories.isEmpty() || prevCategories.isEmpty())) {
            anomalies.append("✅ 未检测到明显异常消费，消费习惯良好！\n");
        }

        return anomalies.toString().trim();
    }

    @Tool("将用户的消费结构与同收入段人群的对标数据进行比较，提供消费结构优化建议")
    public String compareWithBenchmark() {
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
        }
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(6);
        LocalDate monthStart = end.withDayOfMonth(1);

        BigDecimal monthlyIncome = transactionMapper.sumByUserAndTypeAndDateRange(userId, "INCOME", monthStart, end);
        boolean hasIncome = monthlyIncome.compareTo(BigDecimal.ZERO) > 0;

        List<Map<String, Object>> categories = transactionMapper.sumByCategory(userId, start, end);
        if (categories.isEmpty()) {
            return "暂无足够的消费数据进行比较分析，请先录入消费记录";
        }

        BigDecimal totalExpense = categories.stream()
                .map(m -> (BigDecimal) m.get("total"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int months = Math.max(1, (int) (end.toEpochDay() - start.toEpochDay()) / 30);
        BigDecimal avgMonthlyExpense = totalExpense.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);

        Map<String, Benchmark> benchmarks = loadBenchmarks(userId);

        BigDecimal referenceBase = hasIncome ? monthlyIncome : avgMonthlyExpense;

        StringBuilder result = new StringBuilder();
        result.append("消费结构对标分析\n");
        result.append("─────────────────\n");
        result.append(String.format("参考基准（月收入）：%.2f 元\n", referenceBase));
        result.append(String.format("月均支出：%.2f 元\n\n", avgMonthlyExpense));

        int alarmCount = 0;
        for (Map<String, Object> m : categories) {
            String cat = (String) m.get("category");
            BigDecimal amt = (BigDecimal) m.get("total");
            BigDecimal monthlyAvg = amt.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
            double pct = monthlyAvg.divide(referenceBase, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();

            Benchmark bm = benchmarks.getOrDefault(cat, new Benchmark(5, 15, "其他"));
            String status;
            if (pct > bm.max) {
                status = "🔴 偏高";
                alarmCount++;
            } else if (pct < bm.min) {
                status = "🟢 偏低";
            } else {
                status = "✅ 正常";
            }

            result.append(String.format("%s（%s）: 月均 %.2f 元 (%.1f%%)  %s (建议 %.1f%%-%.1f%%)\n",
                    cat, bm.label, monthlyAvg, pct, status, (double) bm.min, (double) bm.max));
        }

        if (hasIncome) {
            double totalPct = avgMonthlyExpense.divide(monthlyIncome, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
            result.append(String.format("\n消费占收入比：%.1f%%", totalPct));
            if (totalPct > 70) {
                result.append(" ⚠️ 消费占比较高，储蓄空间有限");
            } else if (totalPct < 40) {
                result.append(" ✅ 消费控制良好，储蓄率优秀");
            }
        }

        if (alarmCount > 0) {
            result.append("\n\n⚠️ 优化建议：\n");
            result.append("- 标记为「偏高」的分类是重点优化方向\n");
            result.append("- 可通过「消费记录」页面查看具体明细并制定调整计划\n");
        }

        return result.toString().trim();
    }

    @Tool("根据用户的历史消费数据，结合50/30/20法则提供个性化的预算规划方案")
    public String budgetPlanningWizard(
            @P("用户计划每月总预算（元，如8000），如果不确定可填0由系统推算") double userBudget) {
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
        }
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(6);

        BigDecimal totalExpense = transactionMapper.sumByUserAndTypeAndDateRange(userId, "EXPENSE", start, end);
        BigDecimal totalIncome = transactionMapper.sumByUserAndTypeAndDateRange(userId, "INCOME", start, end);
        int months = Math.max(1, (int) (end.toEpochDay() - start.toEpochDay()) / 30);

        BigDecimal avgExpense = totalExpense.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        BigDecimal avgIncome = totalIncome.compareTo(BigDecimal.ZERO) > 0
                ? totalIncome.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        BigDecimal baseAmount;
        if (userBudget > 0) {
            baseAmount = BigDecimal.valueOf(userBudget);
        } else if (avgIncome.compareTo(BigDecimal.ZERO) > 0) {
            baseAmount = avgIncome;
        } else {
            baseAmount = avgExpense;
        }

        BigDecimal needs = baseAmount.multiply(new BigDecimal("0.50"));
        BigDecimal wants = baseAmount.multiply(new BigDecimal("0.30"));
        BigDecimal savings = baseAmount.multiply(new BigDecimal("0.20"));

        List<Map<String, Object>> categories = transactionMapper.sumByCategory(userId, start, end);
        StringBuilder catCurrent = new StringBuilder();
        if (!categories.isEmpty()) {
            catCurrent.append("\n近6月实际月均支出：\n");
            for (Map<String, Object> m : categories) {
                String cat = (String) m.get("category");
                BigDecimal amt = (BigDecimal) m.get("total");
                BigDecimal monthly = amt.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
                catCurrent.append(String.format("  %s: %.2f 元\n", cat, monthly));
            }
        }

        return String.format("""
                个性化预算规划方案
                ─────────────────
                规划基准金额：%.2f 元/月
                %s

                50/30/20 预算分配：
                ┌─────────────────┐
                │ 必要支出 50%%     │ %.2f 元  (房租/房贷、水电、基础伙食、交通)
                │ 品质支出 30%%     │ %.2f 元  (娱乐、购物、旅游、兴趣)
                │ 储蓄投资 20%%     │ %.2f 元  (紧急备用金、基金定投、保险)
                └─────────────────┘

                实施建议：
                - 必要支出超标时优先压缩品质支出，不建议砍储蓄
                - 建议每月1号将20%%部分转入理财账户，养成"先存后花"习惯
                - 每季度复盘一次，根据实际情况动态调整比例
                - 如有大额意外支出，优先从品质支出预算中调配

                下一步：
                - 打开「消费记录」页面查看每日支出明细
                - 使用「异常检测」功能排查是否有不合理消费
                """, baseAmount, catCurrent, needs, wants, savings);
    }

    @Tool("根据用户的收入情况，估算个人所得税（中国内地），并提供节税建议")
    public String taxEstimation() {
        Long userId = validateAndGetUserId();
        if (userId == null) {
            return getAuthErrorMessage();
        }
        LocalDate end = LocalDate.now();
        LocalDate yearStart = end.withDayOfYear(1);

        BigDecimal yearIncome = transactionMapper.sumByUserAndTypeAndDateRange(userId, "INCOME", yearStart, end);
        if (yearIncome.compareTo(BigDecimal.ZERO) == 0) {
            return "暂无本年度收入数据，无法进行个税估算。请在「消费记录」中录入工资等收入记录";
        }

        BigDecimal standardDeduction = new BigDecimal("60000");
        BigDecimal taxableIncome = yearIncome.subtract(standardDeduction);

        BigDecimal tax;
        String bracket;
        if (taxableIncome.compareTo(BigDecimal.ZERO) <= 0) {
            tax = BigDecimal.ZERO;
            bracket = "免征额以下，无需缴税";
        } else if (taxableIncome.compareTo(new BigDecimal("36000")) <= 0) {
            tax = taxableIncome.multiply(new BigDecimal("0.03"));
            bracket = "3%% 税率档";
        } else if (taxableIncome.compareTo(new BigDecimal("144000")) <= 0) {
            tax = taxableIncome.multiply(new BigDecimal("0.10")).subtract(new BigDecimal("2520"));
            bracket = "10%% 税率档";
        } else if (taxableIncome.compareTo(new BigDecimal("300000")) <= 0) {
            tax = taxableIncome.multiply(new BigDecimal("0.20")).subtract(new BigDecimal("16920"));
            bracket = "20%% 税率档";
        } else if (taxableIncome.compareTo(new BigDecimal("420000")) <= 0) {
            tax = taxableIncome.multiply(new BigDecimal("0.25")).subtract(new BigDecimal("31920"));
            bracket = "25%% 税率档";
        } else if (taxableIncome.compareTo(new BigDecimal("660000")) <= 0) {
            tax = taxableIncome.multiply(new BigDecimal("0.30")).subtract(new BigDecimal("52920"));
            bracket = "30%% 税率档";
        } else if (taxableIncome.compareTo(new BigDecimal("960000")) <= 0) {
            tax = taxableIncome.multiply(new BigDecimal("0.35")).subtract(new BigDecimal("85920"));
            bracket = "35%% 税率档";
        } else {
            tax = taxableIncome.multiply(new BigDecimal("0.45")).subtract(new BigDecimal("181920"));
            bracket = "45%% 税率档";
        }

        if (tax.compareTo(BigDecimal.ZERO) < 0) {
            tax = BigDecimal.ZERO;
        }

        double effectiveRate = yearIncome.compareTo(BigDecimal.ZERO) > 0
                ? tax.divide(yearIncome, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue()
                : 0;

        return String.format("""
                个人所得税估算
                ────────────────
                预估年收入：%.2f 元
                基本减除费用：60000 元
                应纳税所得额：%.2f 元
                适用：%s
                预估个税：%.2f 元
                实际税负率：%.1f%%

                节税建议：
                - 如有专项附加扣除（子女教育、房贷利息、赡养老人等），实际缴税会更低
                - 每年3-6月可通过"个人所得税"App进行年度汇算清缴
                - 建议保留相关支出凭证，以便申报时使用
                - 公积金、企业年金等可在税前扣除，如有请一并申报

                ⚠️ 此为基于已录入数据的粗略估算，不构成税务建议，请以税务机关核定为准
                """, yearIncome, taxableIncome, bracket, tax, effectiveRate);
    }

    /**
     * 验证用户身份并获取用户ID，如果验证失败返回null
     */
    private Long validateAndGetUserId() {
        Long userId = UserIdContext.get();
        if (userId == null) {
            log.warn("用户身份验证失败：UserIdContext中无用户信息");
        }
        return userId;
    }

    /**
     * 获取用户身份验证失败时的统一错误消息
     */
    private String getAuthErrorMessage() {
        return "无法获取用户信息，请重新登录后重试";
    }

    private Map<String, Benchmark> loadBenchmarks(Long userId) {
        List<ExpenseCategory> categories = expenseCategoryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExpenseCategory>()
                        .eq(ExpenseCategory::getUserId, 0L)
                        .or()
                        .eq(ExpenseCategory::getUserId, userId)
                        .orderByAsc(ExpenseCategory::getSortOrder)
                        .orderByAsc(ExpenseCategory::getId)
        );
        Map<String, Benchmark> map = new HashMap<>();
        for (ExpenseCategory c : categories) {
            if (c.getBenchmarkMin() != null && c.getBenchmarkMax() != null) {
                map.put(c.getName(), new Benchmark(c.getBenchmarkMin(), c.getBenchmarkMax(),
                        c.getBenchmarkLabel() != null ? c.getBenchmarkLabel() : c.getName()));
            }
        }
        return map;
    }

    private record Benchmark(int min, int max, String label) {}
}
