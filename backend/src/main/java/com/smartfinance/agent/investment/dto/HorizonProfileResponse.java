package com.smartfinance.agent.investment.dto;

import java.util.List;

public record HorizonProfileResponse(String version,
                                     String templateVersion,
                                     String sourceScope,
                                     boolean hasAssetOverride,
                                     int maxHistoryTradingDays,
                                     List<HorizonSettingResponse> settings,
                                     List<String> warnings) {

    public HorizonProfileResponse {
        settings = List.copyOf(settings);
        warnings = List.copyOf(warnings);
    }
}
