package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.IntStream;

@Component
public class PersonalizedActionCalculator {

    private final InvestmentRuntimeProperties properties;

    public PersonalizedActionCalculator(InvestmentRuntimeProperties properties) {
        this.properties = properties;
    }

    public Result calculate(String productType, BigDecimal technicalScore, BigDecimal currentPrice,
                            BigDecimal availableCash, BigDecimal holdingQuantity) {
        BigDecimal score = zero(technicalScore);
        BigDecimal cash = zero(availableCash).max(BigDecimal.ZERO);
        BigDecimal price = zero(currentPrice);
        BigDecimal holding = zero(holdingQuantity).max(BigDecimal.ZERO);
        InvestmentRuntimeProperties.Action action = properties.getAction();
        BigDecimal confidence = clamp(score.subtract(action.getScoreCenter())
                .divide(action.getScoreDistance(), 8, RoundingMode.HALF_UP));
        BigDecimal bearishConfidence = clamp(action.getScoreCenter().subtract(score)
                .divide(action.getScoreDistance(), 8, RoundingMode.HALF_UP));
        BigDecimal suggestedBudget = cash.multiply(confidence);
        BigDecimal singleBudget = suggestedBudget.divide(
                BigDecimal.valueOf(action.getBatchCount()), 8, RoundingMode.DOWN);
        boolean stock = "STOCK".equalsIgnoreCase(productType);
        Batch batch = stock ? stockBatch(singleBudget, price) : new Batch(singleBudget, null);
        List<Batch> batches = IntStream.range(0, action.getBatchCount()).mapToObj(ignored -> batch).toList();
        BigDecimal sellQuantity = stock
                ? boardLot(holding.multiply(bearishConfidence)).min(holding)
                : holding.multiply(bearishConfidence)
                .setScale(action.getFundQuantityScale(), RoundingMode.DOWN).min(holding);
        return new Result(score, confidence.stripTrailingZeros(), bearishConfidence.stripTrailingZeros(),
                suggestedBudget, batches, sellQuantity);
    }

    private Batch stockBatch(BigDecimal budget, BigDecimal price) {
        if (price.signum() <= 0) return new Batch(BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal quantity = boardLot(budget.divide(price, 8, RoundingMode.DOWN));
        return new Batch(quantity.multiply(price), quantity);
    }

    private BigDecimal boardLot(BigDecimal quantity) {
        BigDecimal boardLotSize = properties.getAction().getStockBoardLotSize();
        return quantity.divide(boardLotSize, 0, RoundingMode.DOWN).multiply(boardLotSize);
    }

    private static BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record Batch(BigDecimal amount, BigDecimal quantity) {
    }

    public record Result(BigDecimal technicalScore,
                         BigDecimal technicalConfidence,
                         BigDecimal bearishConfidence,
                         BigDecimal suggestedBudget,
                         List<Batch> batches,
                         BigDecimal sellQuantity) {
    }
}
