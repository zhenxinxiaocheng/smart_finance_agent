package com.smartfinance.agent.investment.dto;

public record HorizonSettingResponse(String code,
                                     String displayName,
                                     int sortOrder,
                                     int minHoldingDays,
                                     int maxHoldingDays,
                                     int targetHoldingDays,
                                     boolean primary,
                                     String sourceScope) {
}
