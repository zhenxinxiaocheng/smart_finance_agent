package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentAccount;
import com.smartfinance.agent.investment.entity.InvestmentCashBalance;
import com.smartfinance.agent.investment.entity.InvestmentPosition;
import com.smartfinance.agent.investment.mapper.InvestmentAccountMapper;
import com.smartfinance.agent.investment.mapper.InvestmentCashBalanceMapper;
import com.smartfinance.agent.investment.mapper.InvestmentPositionMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class QuantTradingDecisionService {
    private final InvestmentAccountMapper accountMapper;
    private final InvestmentCashBalanceMapper cashBalanceMapper;
    private final InvestmentPositionMapper positionMapper;
    private final QuantPaperOrderMapper paperOrderMapper;
    private final QuantPaperFillMapper paperFillMapper;
    private final QuantPaperProperties paperProperties;

    public QuantTradingDecisionService(InvestmentAccountMapper accountMapper,
                                       InvestmentCashBalanceMapper cashBalanceMapper,
                                       InvestmentPositionMapper positionMapper,
                                       QuantPaperOrderMapper paperOrderMapper,
                                       QuantPaperFillMapper paperFillMapper,
                                       QuantPaperProperties paperProperties) {
        this.accountMapper = accountMapper;
        this.cashBalanceMapper = cashBalanceMapper;
        this.positionMapper = positionMapper;
        this.paperOrderMapper = paperOrderMapper;
        this.paperFillMapper = paperFillMapper;
        this.paperProperties = paperProperties;
    }

    public Map<String, Object> paperAccount(Long userId) {
        InvestmentAccount account = accountMapper.selectOne(
                new LambdaQueryWrapper<InvestmentAccount>()
                        .eq(InvestmentAccount::getUserId, userId)
                        .eq(InvestmentAccount::getAccountType, "PAPER")
                        .orderByDesc(InvestmentAccount::getUpdatedAt)
                        .last("LIMIT 1")
        );
        if (account == null) {
            return Map.of(
                    "status", "NOT_STARTED",
                    "cash", List.of(),
                    "positions", List.of()
            );
        }
        List<InvestmentCashBalance> cash = cashBalanceMapper.selectList(
                new LambdaQueryWrapper<InvestmentCashBalance>()
                        .eq(InvestmentCashBalance::getAccountId, account.getId())
        );
        List<InvestmentPosition> positions = positionMapper.selectList(
                new LambdaQueryWrapper<InvestmentPosition>()
                        .eq(InvestmentPosition::getAccountId, account.getId())
        );
        List<QuantPaperOrder> orders = paperOrderMapper.selectList(
                new LambdaQueryWrapper<QuantPaperOrder>()
                        .eq(QuantPaperOrder::getAccountId, account.getId())
                        .orderByDesc(QuantPaperOrder::getSubmittedAt)
                        .last("LIMIT " + paperProperties.getAccountOrderHistoryLimit())
        );
        List<Long> orderIds = orders.stream().map(QuantPaperOrder::getId).toList();
        List<QuantPaperFill> fills = orderIds.isEmpty()
                ? List.of()
                : paperFillMapper.selectList(new LambdaQueryWrapper<QuantPaperFill>()
                        .in(QuantPaperFill::getOrderId, orderIds)
                        .orderByDesc(QuantPaperFill::getFillDate));
        BigDecimal cashCny = cash.stream()
                .filter(item -> "CNY".equals(item.getCurrency()))
                .map(InvestmentCashBalance::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal marketValue = positions.stream()
                .map(InvestmentPosition::getMarketValueCny)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal equity = cashCny.add(marketValue);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ACTIVE");
        result.put("accountId", account.getId());
        result.put("accountName", account.getAccountName());
        result.put("baseCurrency", account.getBaseCurrency());
        result.put("initialCapitalCny", paperProperties.getInitialCashCny());
        result.put("totalEquityCny", equity);
        result.put(
                "totalReturn",
                paperProperties.getInitialCashCny().signum() <= 0
                        ? BigDecimal.ZERO
                        : equity.divide(
                                        paperProperties.getInitialCashCny(),
                                        10,
                                        java.math.RoundingMode.HALF_UP
                                )
                                .subtract(BigDecimal.ONE)
        );
        result.put(
                "paperTradingDays",
                fills.stream().map(QuantPaperFill::getFillDate).distinct().count()
        );
        result.put("cash", cash.stream().map(item -> Map.of(
                "currency", item.getCurrency(),
                "balance", item.getBalance()
        )).toList());
        result.put("positions", positions.stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("productId", item.getProductId());
            value.put("quantity", item.getQuantity());
            value.put("averageCost", item.getAverageCost());
            value.put("latestPrice", item.getLatestPrice());
            value.put("marketValueCny", item.getMarketValueCny());
            value.put("unrealizedPnlCny", item.getUnrealizedPnlCny());
            return value;
        }).toList());
        result.put("orders", orders.stream().map(item -> Map.of(
                "orderId", item.getId(),
                "productId", item.getProductId(),
                "strategyVersion", item.getStrategyVersion(),
                "side", item.getSide(),
                "quantity", item.getQuantity(),
                "status", item.getStatus(),
                "submittedAt", item.getSubmittedAt()
        )).toList());
        return result;
    }
}
