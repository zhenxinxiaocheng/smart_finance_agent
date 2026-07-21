package com.smartfinance.agent.investment.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class PaperOrderCalculator {
    private PaperOrderCalculator() {
    }

    static OrderDraft calculate(String productType, String action, BigDecimal targetWeight,
                                BigDecimal totalEquity, BigDecimal currentQuantity, BigDecimal price,
                                int stockLotSize, int fundQuantityScale, BigDecimal minimumNotional) {
        if (price == null || price.signum() <= 0 || totalEquity == null || totalEquity.signum() <= 0) {
            return OrderDraft.none();
        }
        BigDecimal quantity = currentQuantity == null ? BigDecimal.ZERO : currentQuantity.max(BigDecimal.ZERO);
        BigDecimal currentValue = quantity.multiply(price);
        BigDecimal targetValue;
        if ("EXIT".equals(action)) targetValue = BigDecimal.ZERO;
        else if ("REDUCE".equals(action)) targetValue = currentValue.divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        else if ("ADD".equals(action)) targetValue = totalEquity.multiply(targetWeight == null ? BigDecimal.ZERO : targetWeight);
        else return OrderDraft.none();
        BigDecimal difference = targetValue.subtract(currentValue);
        if (difference.abs().compareTo(minimumNotional) < 0) return OrderDraft.none();
        String side = difference.signum() > 0 ? "BUY" : "SELL";
        BigDecimal rawQuantity = difference.abs().divide(price, 12, RoundingMode.DOWN);
        BigDecimal orderQuantity;
        if ("STOCK".equals(productType)) {
            BigDecimal lot = BigDecimal.valueOf(stockLotSize);
            orderQuantity = rawQuantity.divide(lot, 0, RoundingMode.DOWN).multiply(lot);
        } else {
            orderQuantity = rawQuantity.setScale(fundQuantityScale, RoundingMode.DOWN);
        }
        if ("SELL".equals(side)) orderQuantity = orderQuantity.min(quantity);
        return orderQuantity.signum() <= 0 ? OrderDraft.none() : new OrderDraft(side, orderQuantity);
    }

    record OrderDraft(String side, BigDecimal quantity) {
        static OrderDraft none() {
            return new OrderDraft(null, BigDecimal.ZERO);
        }

        boolean executable() {
            return side != null && quantity.signum() > 0;
        }
    }
}
