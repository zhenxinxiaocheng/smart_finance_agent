package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentPlan;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentPlanMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class InvestmentPlanSimulationService {

    private final InvestmentPlanMapper planMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentAssetService assetService;
    private final InvestmentPlanScheduleCalculator scheduleCalculator;

    public InvestmentPlanSimulationService(InvestmentPlanMapper planMapper,
                                           ProductDailyQuoteMapper quoteMapper,
                                           InvestmentAssetService assetService,
                                           InvestmentPlanScheduleCalculator scheduleCalculator) {
        this.planMapper = planMapper;
        this.quoteMapper = quoteMapper;
        this.assetService = assetService;
        this.scheduleCalculator = scheduleCalculator;
    }

    public List<Long> duePlanIds(LocalDate today, int limit) {
        return planMapper.selectList(new LambdaQueryWrapper<InvestmentPlan>()
                        .eq(InvestmentPlan::getEnabled, 1)
                        .le(InvestmentPlan::getNextExecutionDate, today)
                        .orderByAsc(InvestmentPlan::getNextExecutionDate, InvestmentPlan::getId)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream().map(InvestmentPlan::getId).toList();
    }

    @Transactional
    public void execute(Long planId, LocalDate today) {
        InvestmentPlan plan = planMapper.selectById(planId);
        if (plan == null || !Integer.valueOf(1).equals(plan.getEnabled())
                || plan.getNextExecutionDate() == null || plan.getNextExecutionDate().isAfter(today)) {
            return;
        }

        LocalDate plannedDate = scheduleCalculator.normalize(plan.getNextExecutionDate());
        ProductDailyQuote quote = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, plan.getProductId())
                .eq(ProductDailyQuote::getAdjustType, "NONE")
                .le(ProductDailyQuote::getTradeDate, today)
                .orderByDesc(ProductDailyQuote::getTradeDate, ProductDailyQuote::getId)
                .last("LIMIT 1"));
        if (quote == null || quote.getClosePrice() == null || quote.getClosePrice().signum() <= 0
                || quote.getTradeDate().isBefore(plannedDate)) {
            plan.setLastExecutionStatus("WAITING_DATA");
            plan.setLastExecutionMessage("等待计划日基金净值后自动模拟买入");
            plan.setUpdatedAt(LocalDateTime.now());
            planMapper.updateById(plan);
            return;
        }

        assetService.applyRecurringInvestment(plan.getUserId(), plan.getAccountId(), plan.getProductId(),
                plan.getAmount(), quote.getClosePrice(), plan.getId(), quote.getTradeDate());
        plan.setLastExecutionDate(quote.getTradeDate());
        plan.setLastExecutionAmount(plan.getAmount());
        plan.setLastExecutionPrice(quote.getClosePrice());
        plan.setExecutionCount((plan.getExecutionCount() == null ? 0 : plan.getExecutionCount()) + 1);
        plan.setLastExecutionStatus("SUCCESS");
        plan.setLastExecutionMessage("已按最新净值自动更新模拟持仓");
        plan.setNextExecutionDate(scheduleCalculator.nextAfter(plan, today));
        plan.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(plan);
    }

    @Transactional
    public void markFailed(Long planId, String message) {
        InvestmentPlan plan = planMapper.selectById(planId);
        if (plan == null) return;
        plan.setLastExecutionStatus("FAILED");
        plan.setLastExecutionMessage(limit(message));
        plan.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(plan);
    }

    private static String limit(String value) {
        String message = value == null || value.isBlank() ? "模拟定投执行失败" : value;
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
