package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentAccount;
import com.smartfinance.agent.investment.entity.InvestmentCashBalance;
import com.smartfinance.agent.investment.entity.InvestmentPosition;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentAccountMapper;
import com.smartfinance.agent.investment.mapper.InvestmentCashBalanceMapper;
import com.smartfinance.agent.investment.mapper.InvestmentPositionMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PaperTradingService {
    private static final BigDecimal BPS = new BigDecimal("10000");

    private final InvestmentAccountMapper accountMapper;
    private final InvestmentCashBalanceMapper cashMapper;
    private final InvestmentPositionMapper positionMapper;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final QuantPaperOrderMapper orderMapper;
    private final QuantPaperFillMapper fillMapper;
    private final QuantPaperProperties properties;

    public PaperTradingService(InvestmentAccountMapper accountMapper,
                               InvestmentCashBalanceMapper cashMapper,
                               InvestmentPositionMapper positionMapper,
                               InvestmentProductMapper productMapper,
                               ProductDailyQuoteMapper quoteMapper,
                               QuantPaperOrderMapper orderMapper,
                               QuantPaperFillMapper fillMapper,
                               QuantPaperProperties properties) {
        this.accountMapper = accountMapper;
        this.cashMapper = cashMapper;
        this.positionMapper = positionMapper;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.orderMapper = orderMapper;
        this.fillMapper = fillMapper;
        this.properties = properties;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void queueValidatedPrediction(Long userId, InvestmentProduct product,
                                         QuantPrediction prediction, String modelStatus) {
        if (!List.of("VALIDATED", "PAPER_VERIFIED").contains(modelStatus)
                || prediction.getStrategyVersion() == null) return;
        InvestmentAccount account = ensureAccount(userId);
        if (orderMapper.selectCount(new LambdaQueryWrapper<QuantPaperOrder>()
                .eq(QuantPaperOrder::getPredictionId, prediction.getId())) > 0) return;
        ProductDailyQuote quote = latestQuote(product.getId());
        if (quote == null || quote.getClosePrice() == null) return;
        InvestmentPosition position = position(account.getId(), product.getId());
        BigDecimal currentQuantity = position == null ? BigDecimal.ZERO : position.getQuantity();
        BigDecimal equity = paperEquity(account);
        PaperOrderCalculator.OrderDraft draft = PaperOrderCalculator.calculate(
                product.getProductType(), prediction.getAction(), prediction.getTargetWeight(),
                equity, currentQuantity, quote.getClosePrice(), properties.getStockLotSize(),
                properties.getFundQuantityScale(), properties.getMinimumOrderCny());
        if (!draft.executable()) return;
        QuantPaperOrder order = new QuantPaperOrder();
        order.setUserId(userId);
        order.setAccountId(account.getId());
        order.setProductId(product.getId());
        order.setStrategyVersion(prediction.getStrategyVersion());
        order.setPredictionId(prediction.getId());
        order.setSide(draft.side());
        order.setOrderType(orderType(product, prediction));
        order.setQuantity(draft.quantity());
        order.setStatus("SUBMITTED");
        order.setSubmittedAt(LocalDateTime.now());
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException ignored) {
            // A concurrent worker already persisted the order for this prediction.
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void execute(QuantPaperOrder order) {
        if (order == null || !"SUBMITTED".equals(order.getStatus())) return;
        InvestmentProduct product = productMapper.selectById(order.getProductId());
        if (product == null) {
            reject(order);
            return;
        }
        int delay = executionDelayDays(order, product);
        LocalDate earliest = order.getSubmittedAt().toLocalDate().plusDays(delay);
        ProductDailyQuote quote = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .ge(ProductDailyQuote::getTradeDate, earliest)
                .orderByAsc(ProductDailyQuote::getTradeDate)
                .last("LIMIT 1"));
        if (quote == null || quote.getClosePrice() == null || isLimitLocked(order, quote)) return;
        if ("STOCK".equals(product.getProductType()) && quote.getVolume() != null) {
            BigDecimal maximum = quote.getVolume().multiply(properties.getMaximumVolumeParticipation());
            if (order.getQuantity().compareTo(maximum) > 0) return;
        }
        if (!claimSubmittedOrder(order.getId())) return;
        order.setStatus("PROCESSING");
        if (accountMapper.lockActivePaperAccount(order.getAccountId()) != 1) {
            reject(order);
            return;
        }
        InvestmentCashBalance cash = cash(order.getAccountId());
        InvestmentPosition position = position(order.getAccountId(), product.getId());
        BigDecimal basePrice = quote.getClosePrice();
        BigDecimal slipPerUnit = basePrice.multiply(properties.getSlippageBps()).divide(BPS, 12, RoundingMode.HALF_UP);
        BigDecimal fillPrice = "BUY".equals(order.getSide()) ? basePrice.add(slipPerUnit) : basePrice.subtract(slipPerUnit);
        BigDecimal gross = fillPrice.multiply(order.getQuantity());
        BigDecimal fee = gross.multiply(properties.getFeeBps()).divide(BPS, 8, RoundingMode.HALF_UP);
        if ("BUY".equals(order.getSide()) && cash.getBalance().compareTo(gross.add(fee)) < 0) {
            reject(order);
            return;
        }
        if ("SELL".equals(order.getSide()) && (position == null || position.getQuantity().compareTo(order.getQuantity()) < 0)) {
            reject(order);
            return;
        }
        applyCash(cash, order.getSide(), gross, fee);
        applyPosition(order, position, quote, fillPrice, gross, fee);
        QuantPaperFill fill = new QuantPaperFill();
        fill.setOrderId(order.getId());
        fill.setFillDate(quote.getTradeDate());
        fill.setQuantity(order.getQuantity());
        fill.setPrice(fillPrice);
        fill.setFee(fee);
        fill.setSlippage(slipPerUnit.multiply(order.getQuantity()).setScale(8, RoundingMode.HALF_UP));
        fillMapper.insert(fill);
        order.setStatus("FILLED");
        orderMapper.updateById(order);
    }

    private boolean claimSubmittedOrder(Long orderId) {
        if (orderId == null) return false;
        return orderMapper.claimSubmitted(orderId) == 1;
    }

    private String orderType(InvestmentProduct product, QuantPrediction prediction) {
        if ("STOCK".equals(product.getProductType())) return "A_SHARE_T_PLUS_ONE";
        if ("QDII_INDEX_FUND".equals(prediction.getModelFamily())) return "QDII_UNKNOWN_NAV";
        return "FUND_UNKNOWN_NAV";
    }

    private int executionDelayDays(QuantPaperOrder order, InvestmentProduct product) {
        return switch (order.getOrderType() == null ? "" : order.getOrderType()) {
            case "QDII_UNKNOWN_NAV" -> properties.getQdiiExecutionDelayDays();
            case "FUND_UNKNOWN_NAV" -> properties.getFundExecutionDelayDays();
            case "A_SHARE_T_PLUS_ONE" -> properties.getStockExecutionDelayDays();
            default -> "MUTUAL_FUND".equals(product.getProductType())
                    ? properties.getFundExecutionDelayDays()
                    : properties.getStockExecutionDelayDays();
        };
    }

    private InvestmentAccount ensureAccount(Long userId) {
        InvestmentAccount account = findPaperAccount(userId);
        if (account != null) return account;
        account = new InvestmentAccount();
        account.setUserId(userId);
        account.setAccountName("量化模拟账户");
        account.setAccountType("PAPER");
        account.setBaseCurrency("CNY");
        account.setDeleted(0);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException conflict) {
            InvestmentAccount winner = findPaperAccount(userId);
            if (winner == null) throw conflict;
            return winner;
        }
        InvestmentCashBalance cash = new InvestmentCashBalance();
        cash.setUserId(userId);
        cash.setAccountId(account.getId());
        cash.setCurrency("CNY");
        cash.setBalance(properties.getInitialCashCny());
        cashMapper.insert(cash);
        return account;
    }

    private InvestmentAccount findPaperAccount(Long userId) {
        return accountMapper.selectOne(new LambdaQueryWrapper<InvestmentAccount>()
                .eq(InvestmentAccount::getUserId, userId)
                .eq(InvestmentAccount::getAccountType, "PAPER")
                .eq(InvestmentAccount::getDeleted, 0)
                .last("LIMIT 1"));
    }

    private BigDecimal paperEquity(InvestmentAccount account) {
        BigDecimal cash = cash(account.getId()).getBalance();
        List<InvestmentPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<InvestmentPosition>()
                .eq(InvestmentPosition::getAccountId, account.getId())
                .gt(InvestmentPosition::getQuantity, BigDecimal.ZERO));
        BigDecimal marketValue = positions.stream().map(item -> {
            ProductDailyQuote quote = latestQuote(item.getProductId());
            return quote == null || quote.getClosePrice() == null
                    ? zero(item.getMarketValueCny()) : item.getQuantity().multiply(quote.getClosePrice());
        }).reduce(BigDecimal.ZERO, BigDecimal::add);
        return cash.add(marketValue);
    }

    private InvestmentCashBalance cash(Long accountId) {
        InvestmentCashBalance cash = cashMapper.selectOne(new LambdaQueryWrapper<InvestmentCashBalance>()
                .eq(InvestmentCashBalance::getAccountId, accountId)
                .eq(InvestmentCashBalance::getCurrency, "CNY")
                .last("LIMIT 1"));
        if (cash == null) throw new IllegalStateException("模拟账户现金状态不可用");
        return cash;
    }

    private InvestmentPosition position(Long accountId, Long productId) {
        return positionMapper.selectOne(new LambdaQueryWrapper<InvestmentPosition>()
                .eq(InvestmentPosition::getAccountId, accountId)
                .eq(InvestmentPosition::getProductId, productId)
                .last("LIMIT 1"));
    }

    private ProductDailyQuote latestQuote(Long productId) {
        return quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, productId)
                .orderByDesc(ProductDailyQuote::getTradeDate)
                .last("LIMIT 1"));
    }

    private boolean isLimitLocked(QuantPaperOrder order, ProductDailyQuote quote) {
        if (quote.getHighPrice() == null || quote.getLowPrice() == null || quote.getPreviousClose() == null
                || quote.getPreviousClose().signum() <= 0 || quote.getHighPrice().compareTo(quote.getLowPrice()) != 0) return false;
        BigDecimal move = quote.getClosePrice().divide(quote.getPreviousClose(), 10, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
        BigDecimal threshold = properties.getLimitLockMinimumMoveRatio();
        return ("BUY".equals(order.getSide()) && move.compareTo(threshold) >= 0)
                || ("SELL".equals(order.getSide()) && move.compareTo(threshold.negate()) <= 0);
    }

    private void applyCash(InvestmentCashBalance cash, String side, BigDecimal gross, BigDecimal fee) {
        cash.setBalance("BUY".equals(side)
                ? cash.getBalance().subtract(gross).subtract(fee)
                : cash.getBalance().add(gross).subtract(fee));
        cashMapper.updateById(cash);
    }

    private void applyPosition(QuantPaperOrder order, InvestmentPosition position, ProductDailyQuote quote,
                               BigDecimal fillPrice, BigDecimal gross, BigDecimal fee) {
        InvestmentPosition value = position == null ? new InvestmentPosition() : position;
        if (position == null) {
            value.setUserId(order.getUserId());
            value.setAccountId(order.getAccountId());
            value.setProductId(order.getProductId());
            value.setQuantity(BigDecimal.ZERO);
            value.setCostAmount(BigDecimal.ZERO);
            value.setAverageCost(BigDecimal.ZERO);
            value.setRealizedPnl(BigDecimal.ZERO);
        }
        if ("BUY".equals(order.getSide())) {
            value.setQuantity(value.getQuantity().add(order.getQuantity()));
            value.setCostAmount(value.getCostAmount().add(gross).add(fee));
            value.setAverageCost(value.getCostAmount().divide(value.getQuantity(), 10, RoundingMode.HALF_UP));
        } else {
            BigDecimal costRemoved = value.getAverageCost().multiply(order.getQuantity());
            value.setQuantity(value.getQuantity().subtract(order.getQuantity()));
            value.setCostAmount(value.getCostAmount().subtract(costRemoved).max(BigDecimal.ZERO));
            value.setRealizedPnl(value.getRealizedPnl().add(gross).subtract(fee).subtract(costRemoved));
            if (value.getQuantity().signum() == 0) value.setAverageCost(BigDecimal.ZERO);
        }
        value.setLatestPrice(quote.getClosePrice());
        value.setMarketValueCny(value.getQuantity().multiply(quote.getClosePrice()).setScale(8, RoundingMode.HALF_UP));
        value.setUnrealizedPnlCny(value.getQuantity().multiply(quote.getClosePrice().subtract(value.getAverageCost()))
                .setScale(8, RoundingMode.HALF_UP));
        value.setDataDate(quote.getTradeDate());
        if (value.getId() == null) positionMapper.insert(value); else positionMapper.updateById(value);
    }

    private void reject(QuantPaperOrder order) {
        order.setStatus("REJECTED");
        orderMapper.updateById(order);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
