package com.smartfinance.agent.investment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HorizonSettingRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "周期代码只能包含字母、数字、下划线和连字符")
        @Size(max = 32)
        String code,
        @NotBlank @Size(max = 50) String displayName,
        int sortOrder,
        @Min(1) int minHoldingDays,
        @Min(1) int maxHoldingDays,
        @Min(1) Integer targetHoldingDays,
        boolean primary) {

    public HorizonSettingRequest(String code, String displayName, int sortOrder,
                                 int minHoldingDays, int maxHoldingDays, boolean primary) {
        this(code, displayName, sortOrder, minHoldingDays, maxHoldingDays, null, primary);
    }

    public int resolvedTargetHoldingDays() {
        return targetHoldingDays == null
                ? minHoldingDays + (maxHoldingDays - minHoldingDays) / 2
                : targetHoldingDays;
    }
}
