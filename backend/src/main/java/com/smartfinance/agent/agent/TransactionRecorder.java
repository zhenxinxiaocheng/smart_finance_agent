package com.smartfinance.agent.agent;

import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.service.PendingActionService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
public class TransactionRecorder {

    private final SmartCategorizationService categorizationService;
    private final PendingActionService pendingActionService;

    public TransactionRecorder(SmartCategorizationService categorizationService,
                               PendingActionService pendingActionService) {
        this.categorizationService = categorizationService;
        this.pendingActionService = pendingActionService;
    }

    @Tool("记录一笔收入或支出，但只生成待确认操作，不直接写入交易表。input: {userMessage,type,amount,category,description,date}")
    public String recordTransaction(
            @P("用户原始消息全文") String userMessage,
            @P("交易类型：EXPENSE 或 INCOME") String type,
            @P("金额") BigDecimal amount,
            @P("交易分类") String category,
            @P("交易描述/备注") String description,
            @P("交易日期 yyyy-MM-dd") String date) {

        Long userId = UserIdContext.get();
        if (userId == null) {
            return "无法获取用户信息，请重新登录后重试";
        }

        List<String> validCategories = categorizationService.getAvailableCategories(type, userId);
        if (!validCategories.contains(category)) {
            SmartCategorizationService.CategorizationResult result =
                    categorizationService.categorize(userMessage, type, userId);
            category = result.category();
            log.info("Category auto-corrected: {} -> {}", userMessage, category);
        }

        LocalDate txDate;
        try {
            txDate = LocalDate.parse(date);
        } catch (Exception e) {
            txDate = LocalDate.now();
        }

        try {
            PendingAction action = pendingActionService.prepareTransaction(
                    userId, amount, type, category, description, txDate);
            categorizationService.recordUserPreference(userId, description, category);
            String typeLabel = "INCOME".equals(type) ? "收入" : "支出";
            return String.format(
                    "已生成待确认记账，请用户确认后再入账：%s %.2f 元，分类 %s，日期 %s，备注 %s。待确认ID：%d",
                    typeLabel, amount, category, txDate, description == null ? "" : description, action.getId());
        } catch (Exception e) {
            log.error("Create pending transaction failed: userId={}, error={}", userId, e.getMessage());
            return "生成待确认记账失败：" + e.getMessage() + "。请稍后重试或在「消费记录」页面手动录入。";
        }
    }

    @Tool("根据用户输入获取分类建议。input: {userMessage,type}")
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
        sb.append("\n请根据以上推荐选择合适分类，再调用 recordTransaction 生成待确认记账。");
        return sb.toString();
    }
}
