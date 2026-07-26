package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.service.WealthService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class QuantActionPlanService {
    private static final BigDecimal STOCK_BOARD_LOT = BigDecimal.valueOf(100);

    private final QuantPredictionQueryService predictionQueryService;
    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final WealthService wealthService;

    public QuantActionPlanService(QuantPredictionQueryService predictionQueryService,
                                  InvestmentAssetMapper assetMapper,
                                  InvestmentProductMapper productMapper,
                                  ProductDailyQuoteMapper quoteMapper,
                                  WealthService wealthService) {
        this.predictionQueryService = predictionQueryService;
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.wealthService = wealthService;
    }

    public Map<String, Object> actionPlan(Long userId, Long assetId, String horizonCode) {
        InvestmentAsset asset = requireAsset(userId, assetId);
        InvestmentProduct product = requireProduct(asset.getProductId());
        Map<String, Object> analysis =
                predictionQueryService.latestAnalysis(userId, assetId, horizonCode);
        if (!"READY".equals(analysis.get("status"))) {
            return pausedPlan(
                    assetId,
                    horizonCode,
                    String.valueOf(analysis.getOrDefault(
                            "userMessage",
                            "暂无有效量化模型"
                    )),
                    stringList(analysis.get("riskFlags"))
            );
        }

        BigDecimal targetWeight = decimal(analysis.get("recommendedTargetWeight"));
        BigDecimal currentWeight = decimal(analysis.get("currentWeight"));
        if (targetWeight == null || currentWeight == null) {
            return pausedPlan(
                    assetId,
                    horizonCode,
                    "模型未生成可执行仓位，当前暂停操作",
                    List.of("POSITION_UNAVAILABLE")
            );
        }
        BigDecimal weightDelta = targetWeight.subtract(currentWeight);
        String action = normalizeAction(String.valueOf(analysis.get("action")), weightDelta);
        WealthOverviewResponse wealth = wealthService.overview(userId);
        BigDecimal totalAssets = wealth == null ? null : wealth.getTotalAssets();
        BigDecimal orderAmount = executableAmount(action, weightDelta, totalAssets);
        BigDecimal latestPrice = latestPrice(product.getId());
        BigDecimal estimatedQuantity = estimatedQuantity(
                product.getProductType(),
                orderAmount,
                latestPrice
        );

        LocalDateTime generatedAt = LocalDateTime.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "READY");
        result.put("assetId", assetId);
        result.put("horizonCode", horizonCode.toUpperCase(Locale.ROOT));
        result.put("modelVersion", analysis.get("modelVersion"));
        result.put("strategyVersion", analysis.get("strategyVersion"));
        result.put("action", action);
        result.put("generatedAt", generatedAt);
        result.put("executeFrom", generatedAt);
        result.put("validUntil", generatedAt.plusDays(1));
        result.put("currentWeight", currentWeight);
        result.put("targetWeight", targetWeight);
        result.put("weightDelta", weightDelta);
        result.put("orderAmountCny", orderAmount);
        result.put("estimatedQuantity", estimatedQuantity);
        result.put("executionNote", executionNote(product.getProductType(), action));
        result.put("stopConditions", stopConditions());
        result.put("profitProbability", analysis.get("profitProbability"));
        result.put("lossProbability", analysis.get("lossProbability"));
        result.put("expectedNetReturn", analysis.get("expectedNetReturn"));
        result.put("predictionInterval", analysis.get("predictionInterval"));
        result.put("riskFlags", analysis.getOrDefault("riskFlags", List.of()));
        result.put("userMessage", actionMessage(action, orderAmount));
        return result;
    }

    private Map<String, Object> pausedPlan(Long assetId,
                                           String horizonCode,
                                           String userMessage,
                                           List<String> riskFlags) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PAUSED");
        result.put("assetId", assetId);
        result.put("horizonCode", horizonCode.toUpperCase(Locale.ROOT));
        result.put("action", "PAUSE");
        result.put("generatedAt", LocalDateTime.now());
        result.put("executeFrom", null);
        result.put("validUntil", null);
        result.put("currentWeight", null);
        result.put("targetWeight", null);
        result.put("weightDelta", null);
        result.put("orderAmountCny", null);
        result.put("estimatedQuantity", null);
        result.put("executionNote", "当前不提交模拟订单");
        result.put("stopConditions", stopConditions());
        result.put("riskFlags", riskFlags);
        result.put("userMessage", userMessage);
        return result;
    }

    private InvestmentAsset requireAsset(Long userId, Long assetId) {
        InvestmentAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<InvestmentAsset>()
                .eq(InvestmentAsset::getId, assetId)
                .eq(InvestmentAsset::getUserId, userId)
                .last("LIMIT 1"));
        if (asset == null) {
            throw new IllegalArgumentException("投资资产不存在");
        }
        return asset;
    }

    private InvestmentProduct requireProduct(Long productId) {
        InvestmentProduct product = productMapper.selectById(productId);
        if (product == null) {
            throw new IllegalArgumentException("投资产品不存在");
        }
        return product;
    }

    private BigDecimal latestPrice(Long productId) {
        ProductDailyQuote quote = quoteMapper.selectOne(
                new LambdaQueryWrapper<ProductDailyQuote>()
                        .eq(ProductDailyQuote::getProductId, productId)
                        .orderByDesc(ProductDailyQuote::getTradeDate)
                        .last("LIMIT 1")
        );
        return quote == null ? null : quote.getClosePrice();
    }

    private static BigDecimal executableAmount(String action,
                                               BigDecimal weightDelta,
                                               BigDecimal totalAssets) {
        if (totalAssets == null || totalAssets.signum() <= 0
                || !List.of("BUY", "ADD", "REDUCE", "EXIT").contains(action)) {
            return null;
        }
        return totalAssets.multiply(weightDelta.abs()).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal estimatedQuantity(String productType,
                                                BigDecimal amount,
                                                BigDecimal latestPrice) {
        if (amount == null || latestPrice == null || latestPrice.signum() <= 0) {
            return null;
        }
        BigDecimal raw = amount.divide(latestPrice, 10, RoundingMode.DOWN);
        if ("STOCK".equalsIgnoreCase(productType)) {
            return raw.divide(STOCK_BOARD_LOT, 0, RoundingMode.DOWN)
                    .multiply(STOCK_BOARD_LOT);
        }
        return raw.setScale(4, RoundingMode.DOWN);
    }

    private static String normalizeAction(String rawAction, BigDecimal weightDelta) {
        String action = rawAction == null
                ? "PAUSE"
                : rawAction.trim().toUpperCase(Locale.ROOT);
        action = switch (action) {
            case "BUY_WATCH" -> "BUY";
            case "NO_TRADE" -> "PAUSE";
            default -> action;
        };
        if (List.of("BUY", "ADD").contains(action) && weightDelta.signum() <= 0) {
            return "HOLD";
        }
        if (List.of("REDUCE", "EXIT").contains(action) && weightDelta.signum() >= 0) {
            return "HOLD";
        }
        return List.of("BUY", "ADD", "HOLD", "REDUCE", "EXIT", "PAUSE").contains(action)
                ? action
                : "PAUSE";
    }

    private static String executionNote(String productType, String action) {
        if (!List.of("BUY", "ADD", "REDUCE", "EXIT").contains(action)) {
            return "当前无需提交订单，等待下一次模型检查";
        }
        if ("STOCK".equalsIgnoreCase(productType)) {
            return "按A股整手规则生成模拟委托，并受T+1、涨跌停和流动性限制";
        }
        return "按基金未知净值规则生成模拟申赎，实际份额以确认日净值为准";
    }

    private static List<String> stopConditions() {
        return List.of(
                "模型过期、数据过期或官方基准不可用时立即暂停",
                "模型连续三个监控窗口失败时停止使用并回滚",
                "预测区间或风险预算不再支持当前仓位时减仓或退出"
        );
    }

    private static String actionMessage(String action, BigDecimal orderAmount) {
        return switch (action) {
            case "BUY" -> "满足建仓条件，按计划金额买入";
            case "ADD" -> "满足加仓条件，按计划金额追加";
            case "REDUCE" -> "风险上升，按计划金额减仓";
            case "EXIT" -> "退出条件触发，按计划金额卖出";
            case "HOLD" -> "继续持有，暂不调整仓位";
            default -> orderAmount == null ? "当前暂停操作" : "等待下一次模型检查";
        };
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
