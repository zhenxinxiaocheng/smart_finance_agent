package com.smartfinance.agent.investment.config;

import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "investment.analysis-horizons")
@PropertySource(value = "classpath:investment-horizons.properties", encoding = "UTF-8")
public class InvestmentHorizonProperties {

    private String templateVersion;
    private int maxHistoryTradingDays;
    private List<TemplateHorizon> defaults = new ArrayList<>();

    public ResolvedHorizonProfile toTemplateProfile() {
        if (templateVersion == null || templateVersion.isBlank()) {
            throw new IllegalStateException("investment.analysis-horizons.template-version 未配置");
        }
        if (maxHistoryTradingDays < 1) {
            throw new IllegalStateException("investment.analysis-horizons.max-history-trading-days 必须为正整数");
        }
        List<HorizonSetting> settings = defaults.stream()
                .map(item -> new HorizonSetting(
                        item.getCode(), item.getDisplayName(), item.getSortOrder(),
                        item.getMinHoldingDays(), item.getMaxHoldingDays(),
                        item.isPrimary(), "TEMPLATE"))
                .toList();
        return new ResolvedHorizonProfile(
                "template:" + templateVersion.trim(), templateVersion.trim(), settings, List.of());
    }

    @Data
    public static class TemplateHorizon {
        private String code;
        private String displayName;
        private int sortOrder;
        private int minHoldingDays;
        private int maxHoldingDays;
        private boolean primary;
    }
}
