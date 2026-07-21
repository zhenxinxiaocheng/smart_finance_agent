package com.smartfinance.agent.investment.domain;

import java.math.BigDecimal;

public class InvestmentLedgerCalculator {

    public LedgerState apply(LedgerState state, LedgerEvent event) {
        return switch (event.type()) {
            case "BUY", "DIVIDEND_REINVEST" -> buy(state, event);
            case "SELL", "TRANSFER_OUT" -> sell(state, event);
            case "DIVIDEND" -> dividend(state, event);
            case "SPLIT", "MERGE" -> split(state, event);
            default -> throw new IllegalArgumentException("Unsupported investment event: " + event.type());
        };
    }

    private LedgerState buy(LedgerState state, LedgerEvent event) {
        positive(event.quantity(), "quantity");
        positive(event.price(), "price");
        BigDecimal tradeAmount = event.quantity().multiply(event.price());
        BigDecimal costIncrease = tradeAmount.add(zero(event.fee()));
        return new LedgerState(
                state.quantity().add(event.quantity()),
                state.costAmount().add(costIncrease),
                state.realizedPnl(),
                state.cashBalance().subtract(costIncrease)
        );
    }

    private LedgerState sell(LedgerState state, LedgerEvent event) {
        positive(event.quantity(), "quantity");
        positive(event.price(), "price");
        if (event.quantity().compareTo(state.quantity()) > 0) {
            throw new IllegalArgumentException("Sell quantity exceeds current position");
        }
        BigDecimal averageCost = state.averageCost();
        BigDecimal costReduction = averageCost.multiply(event.quantity());
        BigDecimal proceeds = event.quantity().multiply(event.price()).subtract(zero(event.fee()));
        return new LedgerState(
                state.quantity().subtract(event.quantity()),
                state.costAmount().subtract(costReduction),
                state.realizedPnl().add(proceeds.subtract(costReduction)),
                state.cashBalance().add(proceeds)
        );
    }

    private LedgerState dividend(LedgerState state, LedgerEvent event) {
        positive(event.amount(), "amount");
        return new LedgerState(
                state.quantity(),
                state.costAmount(),
                state.realizedPnl().add(event.amount()),
                state.cashBalance().add(event.amount())
        );
    }

    private LedgerState split(LedgerState state, LedgerEvent event) {
        positive(event.factor(), "factor");
        return new LedgerState(
                state.quantity().multiply(event.factor()),
                state.costAmount(),
                state.realizedPnl(),
                state.cashBalance()
        );
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static void positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
