package com.smartfinance.agent.investment.domain;

import java.util.Locale;
import java.util.Objects;

public record HorizonSetting(String code,
                             String displayName,
                             int sortOrder,
                             int minHoldingDays,
                             int maxHoldingDays,
                             boolean primary,
                             String sourceScope) {

    public HorizonSetting {
        code = Objects.requireNonNull(code, "周期代码不能为空").trim().toUpperCase(Locale.ROOT);
        displayName = Objects.requireNonNull(displayName, "周期名称不能为空").trim();
        sourceScope = Objects.requireNonNull(sourceScope, "周期来源不能为空").trim().toUpperCase(Locale.ROOT);
        if (code.isBlank() || displayName.isBlank() || sourceScope.isBlank()) {
            throw new IllegalArgumentException("周期代码、名称和来源不能为空");
        }
        if (minHoldingDays < 1 || maxHoldingDays < minHoldingDays) {
            throw new IllegalArgumentException("周期天数必须为正整数，且最小天数不能大于最大天数");
        }
    }
}
