package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.entity.FinancialProfile;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class InvestmentFinancialWarningEngine {
    private final InvestmentRuntimeProperties runtimeProperties;

    public InvestmentFinancialWarningEngine(
            InvestmentRuntimeProperties runtimeProperties
    ) {
        this.runtimeProperties = runtimeProperties;
    }

    public List<Map<String, Object>> evaluate(Input input) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        InvestmentRuntimeProperties.Risk risk = runtimeProperties.getRisk();
        WealthOverviewResponse wealth = input.wealth();
        FinancialProfile profile = input.profile();
        if (wealth == null || !wealth.isInitialized()) {
            warnings.add(warning(
                    input,
                    "WEALTH_NOT_INITIALIZED",
                    "INFO",
                    "尚未建立现金基准，数量参考只使用投资账户中的可用现金",
                    Map.of("wealthInitialized", false)
            ));
        }
        if (profile != null && wealth != null && wealth.isInitialized()
                && profile.getFixedExpense() != null
                && wealth.getDailyCash() != null) {
            BigDecimal reserveTarget = profile.getFixedExpense().multiply(
                    risk.getEmergencyReserveMonths()
            );
            if (wealth.getDailyCash().compareTo(reserveTarget) < 0) {
                warnings.add(warning(
                        input,
                        "RESERVE_LOW",
                        "WARNING",
                        "备用金低于 "
                                + plain(risk.getEmergencyReserveMonths())
                                + " 个月固定支出，新增投资前应先补足现金缓冲",
                        Map.of(
                                "dailyCash", wealth.getDailyCash(),
                                "reserveTarget", reserveTarget
                        )
                ));
            }
        }
        if (wealth != null && wealth.getTotalAssets() != null
                && wealth.getTotalAssets().signum() > 0
                && input.marketValueCny() != null) {
            BigDecimal concentration = input.marketValueCny().divide(
                    wealth.getTotalAssets(),
                    6,
                    RoundingMode.HALF_UP
            ).multiply(BigDecimal.valueOf(100));
            if (concentration.compareTo(
                    risk.getAssetConcentrationWarningPercent()
            ) > 0) {
                warnings.add(warning(
                        input,
                        "CONCENTRATION_HIGH",
                        "WARNING",
                        "该资产约占总资产 "
                                + concentration.setScale(1, RoundingMode.HALF_UP)
                                + "%，单一资产风险较集中",
                        Map.of(
                                "concentrationPercent", concentration,
                                "warningPercent",
                                risk.getAssetConcentrationWarningPercent()
                        )
                ));
            }
        }
        if (input.annualizedVolatilityPercent() != null
                && input.annualizedVolatilityPercent()
                > risk.getVolatilityWarningPercent().doubleValue()) {
            warnings.add(warning(
                    input,
                    "VOLATILITY_HIGH",
                    "WARNING",
                    "近期年化波动约 "
                            + percent(input.annualizedVolatilityPercent())
                            + "，价格起伏明显高于警戒线",
                    Map.of(
                            "annualizedVolatilityPercent",
                            input.annualizedVolatilityPercent(),
                            "warningPercent",
                            risk.getVolatilityWarningPercent()
                    )
            ));
        }
        if (input.maxDrawdownPercent() != null
                && Math.abs(input.maxDrawdownPercent())
                > risk.getDrawdownWarningPercent().doubleValue()) {
            warnings.add(warning(
                    input,
                    "DRAWDOWN_HIGH",
                    "WARNING",
                    "历史最大回撤约 "
                            + percent(Math.abs(input.maxDrawdownPercent()))
                            + "，需要预留较大的亏损承受空间",
                    Map.of(
                            "maximumDrawdownPercent",
                            input.maxDrawdownPercent(),
                            "warningPercent",
                            risk.getDrawdownWarningPercent()
                    )
            ));
        }
        if (input.turnoverRatePercent() != null
                && input.turnoverRatePercent().compareTo(
                        risk.getMinimumTurnoverRatePercent()
                ) < 0) {
            warnings.add(warning(
                    input,
                    "LIQUIDITY_LOW",
                    "WARNING",
                    "近期换手率偏低，实际成交价格可能与页面参考价存在偏差",
                    Map.of(
                            "turnoverRatePercent", input.turnoverRatePercent(),
                            "minimumPercent",
                            risk.getMinimumTurnoverRatePercent()
                    )
            ));
        }
        if (input.dataDecision() != null
                && !"ALLOW".equalsIgnoreCase(input.dataDecision())) {
            warnings.add(warning(
                    input,
                    "DATA_INCOMPLETE",
                    "WARNING",
                    "数据完整性尚未通过，当前分析只能使用已验证的历史缓存",
                    Map.of("dataDecision", input.dataDecision())
            ));
        }
        if (profile != null && wealth != null
                && profile.getSavingsGoalAmount() != null
                && wealth.isInitialized()
                && wealth.getTotalAssets() != null
                && wealth.getTotalAssets().compareTo(
                        profile.getSavingsGoalAmount()
                ) < 0) {
            warnings.add(warning(
                    input,
                    "SAVINGS_GOAL",
                    "INFO",
                    "当前总资产尚未达到已设置的储蓄目标",
                    Map.of(
                            "currentAssets", wealth.getTotalAssets(),
                            "savingsGoal", profile.getSavingsGoalAmount()
                    )
            ));
        }
        addRiskPreferenceWarning(warnings, input, profile, risk);
        return List.copyOf(warnings);
    }

    private void addRiskPreferenceWarning(
            List<Map<String, Object>> warnings,
            Input input,
            FinancialProfile profile,
            InvestmentRuntimeProperties.Risk risk
    ) {
        if (profile == null || input.technicalScore() == null) {
            return;
        }
        if ("CONSERVATIVE".equals(profile.getRiskPreference())
                && input.technicalScore().compareTo(
                        risk.getConservativeStrongScore()
                ) >= 0) {
            warnings.add(warning(
                    input,
                    "RISK_PREFERENCE",
                    "INFO",
                    "技术条件偏强，但你的风险偏好较保守，仍应限制仓位并分批执行",
                    Map.of("technicalScore", input.technicalScore())
            ));
        } else if ("AGGRESSIVE".equals(profile.getRiskPreference())
                && input.technicalScore().compareTo(
                        risk.getAggressiveWeakScore()
                ) < 0) {
            warnings.add(warning(
                    input,
                    "RISK_PREFERENCE",
                    "WARNING",
                    "风险偏好较进取不会改变当前技术偏弱的事实",
                    Map.of("technicalScore", input.technicalScore())
            ));
        }
    }

    private Map<String, Object> warning(
            Input input,
            String code,
            String severity,
            String message,
            Map<String, Object> evidence
    ) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("assetId", input.assetId());
        provenance.put("assetName", input.assetName());
        provenance.put("horizonCode", input.horizonCode());
        provenance.put("datasetVersion", input.datasetVersion());
        ZoneId zone = runtimeProperties.getMarket().getZone();
        provenance.put(
                "calculatedAt",
                LocalDateTime.now(zone == null ? ZoneId.systemDefault() : zone)
        );
        provenance.put(
                "ruleVersion",
                runtimeProperties.getRisk().getPortfolioRuleVersion()
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("severity", severity);
        result.put("message", message);
        result.put("affectsTechnicalAnalysis", false);
        result.put("sourceType", "RULE_ENGINE");
        result.put("evidence", evidence);
        result.put("provenance", provenance);
        return result;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String percent(double value) {
        return BigDecimal.valueOf(value)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
                + "%";
    }

    public record Input(
            Long assetId,
            String assetName,
            BigDecimal marketValueCny,
            BigDecimal turnoverRatePercent,
            WealthOverviewResponse wealth,
            FinancialProfile profile,
            BigDecimal technicalScore,
            Double annualizedVolatilityPercent,
            Double maxDrawdownPercent,
            String dataDecision,
            String horizonCode,
            String datasetVersion
    ) {
    }
}
