package com.smartfinance.agent.agent;

import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.service.TransactionService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 智能记账工具 - 供AI调用的交易记录工具
 */
@Slf4j
@Component
public class TransactionRecorder {

    private final TransactionService transactionService;
    private final SmartCategorizationService categorizationService;

    public TransactionRecorder(TransactionService transactionService,
                                SmartCategorizationService categorizationService) {
        this.transactionService = transactionService;
        this.categorizationService = categorizationService;
    }

    /**
     * 记录一笔交易。AI从用户自然语言中提取出结构化信息后调用此工具。
     */
    @Tool("记录一笔消费或收入交易。当用户表示要记账、记录消费、记录收入时，先提取信息再调用此工具。" +
          "type必须是EXPENSE或INCOME，category必须是系统预定义的分类之一，amount是数字金额，date格式yyyy-MM-dd")
    public String recordTransaction(
            @P("用户原始消息全文") String userMessage,
            @P("交易类型：EXPENSE(支出) 或 INCOME(收入)") String type,
            @P("金额(数字)") BigDecimal amount,
            @P("交易分类，必须从系统可用分类中选择（请通过分类列表了解当前可用分类）") String category,
            @P("交易描述/备注") String description,
            @P("交易日期(yyyy-MM-dd)，如未指定则填今天") String date) {

        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }

        // 验证分类是否有效
        List<String> validCategories = categorizationService.getAvailableCategories(type, userId);
        if (!validCategories.contains(category)) {
            SmartCategorizationService.CategorizationResult result =
                    categorizationService.categorize(userMessage, type, userId);
            category = result.category();
            log.info("分类自动修正: {} -> {}", userMessage, category);
        }

        LocalDate txDate;
        try {
            txDate = LocalDate.parse(date);
        } catch (Exception e) {
            txDate = LocalDate.now();
        }

        try {
            transactionService.add(userId, amount, type, category, description, txDate);
            log.info("AI记账成功: userId={}, type={}, amount={}, category={}, date={}",
                    userId, type, amount, category, txDate);

            // 学习用户偏好
            categorizationService.recordUserPreference(userId, description, category);

            String typeLabel = "EXPENSE".equals(type) ? "支出" : "收入";
            return String.format("✅ 记账成功！\n" +
                    "类型：%s\n" +
                    "分类：%s\n" +
                    "金额：%.2f 元\n" +
                    "日期：%s\n" +
                    "备注：%s\n\n" +
                    "请用友好的语气告知用户记账成功，并简述这笔记录的信息。",
                    typeLabel, category, amount, txDate, description != null ? description : "");
        } catch (Exception e) {
            log.error("AI记账失败: userId={}, error={}", userId, e.getMessage());
            return "记账失败：" + e.getMessage() + "。请告知用户稍后重试或在「消费记录」页面手动录入。";
        }
    }

    /**
     * 查询分类建议。当AI不确定该用哪个分类时调用此工具获取建议。
     */
    @Tool("根据用户输入获取分类建议。当AI无法确定交易分类时调用。返回推荐的分类列表和置信度。")
    public String suggestCategory(
            @P("用户原始消息") String userMessage,
            @P("交易类型：EXPENSE/INCOME") String type) {

        Long userId = UserIdContext.get();

        SmartCategorizationService.CategorizationResult result =
                categorizationService.categorize(userMessage, type, userId);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("推荐分类：%s（置信度：%.0f%%）\n", result.category(), result.confidence() * 100));

        if (!result.alternatives().isEmpty()) {
            sb.append("备选分类：").append(String.join("、", result.alternatives())).append("\n");
        }

        sb.append("\n请根据以上推荐，选择合适的分类调用 recordTransaction 工具完成记账。");
        sb.append("如果置信度低于80%，请在回复中提示用户确认分类是否正确。");

        return sb.toString();
    }
}