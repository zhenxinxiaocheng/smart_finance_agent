package com.smartfinance.agent.investment.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "investment.runtime")
@PropertySource(value = "classpath:investment-runtime.properties", encoding = "UTF-8")
public class InvestmentRuntimeProperties {

    private String parameterVersion;
    private Risk risk = new Risk();
    private Sync sync = new Sync();
    private Market market = new Market();
    private Api api = new Api();
    private Plan plan = new Plan();
    private DataQuality dataQuality = new DataQuality();
    private Analysis analysis = new Analysis();

    @PostConstruct
    public void validate() {
        requireText(parameterVersion, "parameter-version");
        requirePositive(risk.emergencyReserveMonths, "risk.emergency-reserve-months");
        requirePercent(risk.assetConcentrationWarningPercent, "risk.asset-concentration-warning-percent");
        requireRatio(risk.portfolioConcentrationWarningRatio, "risk.portfolio-concentration-warning-ratio");
        requirePercent(risk.conservativeStrongScore, "risk.conservative-strong-score");
        requirePercent(risk.aggressiveWeakScore, "risk.aggressive-weak-score");
        requirePercent(risk.volatilityWarningPercent, "risk.volatility-warning-percent");
        requirePercent(risk.drawdownWarningPercent, "risk.drawdown-warning-percent");
        requirePercent(risk.minimumTurnoverRatePercent, "risk.minimum-turnover-rate-percent");
        requireText(risk.portfolioRuleVersion, "risk.portfolio-rule-version");
        requirePositive(sync.pollDelayMs, "sync.poll-delay-ms");
        requirePositive(sync.initialDelayMs, "sync.initial-delay-ms");
        requirePositive(sync.batchLimit, "sync.batch-limit");
        requirePositive(sync.errorMessageMaxLength, "sync.error-message-max-length");
        requirePositive(sync.fxLookbackCalendarDays, "sync.fx-lookback-calendar-days");
        if (market.zone == null || market.stockRefreshStart == null || market.stockRefreshEnd == null
                || market.fundRefreshStart == null || market.fundRefreshEnd == null) {
            throw invalid("market 时区或刷新时段未配置");
        }
        requirePositive(market.stockRefreshIntervalMs, "market.stock-refresh-interval-ms");
        requirePositive(market.stockActiveFreshnessMs, "market.stock-active-freshness-ms");
        requirePositive(market.fundInitialDelayMs, "market.fund-initial-delay-ms");
        requirePositive(market.fundRefreshIntervalMs, "market.fund-refresh-interval-ms");
        requirePositive(market.fundActiveFreshnessMs, "market.fund-active-freshness-ms");
        requirePositive(market.activeRefreshConcurrency, "market.active-refresh-concurrency");
        requirePositive(market.calendarCacheHours, "market.calendar-cache-hours");
        requirePositive(market.calendarSearchLimitDays, "market.calendar-search-limit-days");
        requirePositive(api.productSearchLimit, "api.product-search-limit");
        requirePositive(api.defaultTransactionLimit, "api.default-transaction-limit");
        requirePositive(api.maxTransactionLimit, "api.max-transaction-limit");
        if (api.maxTransactionLimit < api.defaultTransactionLimit) {
            throw invalid("api.max-transaction-limit 不能小于 default-transaction-limit");
        }
        requirePositive(api.importMaxBytes, "api.import-max-bytes");
        if (plan.executionDayMaximums == null || plan.executionDayMaximums.isEmpty()
                || plan.executionDayMaximums.values().stream().anyMatch(value -> value == null || value < 1)) {
            throw invalid("plan.execution-day-maximums 配置不完整");
        }
        requireText(dataQuality.configVersion, "data-quality.config-version");
        requireText(dataQuality.frequency, "data-quality.frequency");
        requireText(dataQuality.stockAdjustType, "data-quality.stock-adjust-type");
        requireText(dataQuality.fundAdjustType, "data-quality.fund-adjust-type");
        requireText(dataQuality.realtimeAdjustType, "data-quality.realtime-adjust-type");
        if (!"QFQ".equals(dataQuality.stockAdjustType)) {
            throw invalid("data-quality.stock-adjust-type 必须为 QFQ");
        }
        if (!"NONE".equals(dataQuality.fundAdjustType)) {
            throw invalid("data-quality.fund-adjust-type 必须为 NONE");
        }
        requireText(analysis.strategyVersion, "analysis.strategy-version");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field + " 未配置");
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) throw invalid(field + " 必须大于 0");
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) throw invalid(field + " 必须大于 0");
    }

    private static void requirePercent(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw invalid(field + " 必须在 0 到 100 之间");
        }
    }

    private static void requireRatio(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw invalid(field + " 必须在 0 到 1 之间");
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("investment.runtime." + message);
    }

    @Data
    public static class Risk {
        private BigDecimal emergencyReserveMonths;
        private BigDecimal assetConcentrationWarningPercent;
        private BigDecimal portfolioConcentrationWarningRatio;
        private BigDecimal conservativeStrongScore;
        private BigDecimal aggressiveWeakScore;
        private BigDecimal volatilityWarningPercent;
        private BigDecimal drawdownWarningPercent;
        private BigDecimal minimumTurnoverRatePercent;
        private String portfolioRuleVersion;
    }

    @Data
    public static class Sync {
        private long initialDelayMs;
        private long pollDelayMs;
        private int batchLimit;
        private int errorMessageMaxLength;
        private int fxLookbackCalendarDays;
    }

    @Data
    public static class Market {
        private ZoneId zone;
        private long stockRefreshIntervalMs;
        private long stockActiveFreshnessMs;
        private LocalTime stockRefreshStart;
        private LocalTime stockRefreshEnd;
        private long fundInitialDelayMs;
        private long fundRefreshIntervalMs;
        private long fundActiveFreshnessMs;
        private int activeRefreshConcurrency;
        private LocalTime fundRefreshStart;
        private LocalTime fundRefreshEnd;
        private long calendarCacheHours;
        private int calendarSearchLimitDays;
        private List<LocalDate> fallbackClosedDates = new ArrayList<>();
    }

    @Data
    public static class Api {
        private int productSearchLimit;
        private int defaultTransactionLimit;
        private int maxTransactionLimit;
        private long importMaxBytes;
    }

    @Data
    public static class Plan {
        private Map<String, Integer> executionDayMaximums = new LinkedHashMap<>();
    }

    @Data
    public static class DataQuality {
        private String configVersion;
        private String frequency;
        private String stockAdjustType;
        private String fundAdjustType;
        private String realtimeAdjustType;
    }

    @Data
    public static class Analysis {
        private String strategyVersion;
    }
}
