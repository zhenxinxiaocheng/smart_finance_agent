package com.smartfinance.agent.investment.domain;

import java.math.BigDecimal;

public record LedgerEvent(
        String type,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee,
        BigDecimal amount,
        BigDecimal factor
) {
    public static LedgerEvent buy(BigDecimal quantity, BigDecimal price, BigDecimal fee) {
        return new LedgerEvent("BUY", quantity, price, fee, null, null);
    }

    public static LedgerEvent sell(BigDecimal quantity, BigDecimal price, BigDecimal fee) {
        return new LedgerEvent("SELL", quantity, price, fee, null, null);
    }

    public static LedgerEvent dividend(BigDecimal amount) {
        return new LedgerEvent("DIVIDEND", null, null, null, amount, null);
    }

    public static LedgerEvent split(BigDecimal factor) {
        return new LedgerEvent("SPLIT", null, null, null, null, factor);
    }
}
