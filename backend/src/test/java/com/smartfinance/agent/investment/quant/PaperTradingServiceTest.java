package com.smartfinance.agent.investment.quant;

import com.smartfinance.agent.investment.entity.InvestmentCashBalance;
import com.smartfinance.agent.investment.entity.InvestmentAccount;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentAccountMapper;
import com.smartfinance.agent.investment.mapper.InvestmentCashBalanceMapper;
import com.smartfinance.agent.investment.mapper.InvestmentPositionMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperTradingServiceTest {
    @Mock private InvestmentAccountMapper accountMapper;
    @Mock private InvestmentCashBalanceMapper cashMapper;
    @Mock private InvestmentPositionMapper positionMapper;
    @Mock private InvestmentProductMapper productMapper;
    @Mock private ProductDailyQuoteMapper quoteMapper;
    @Mock private QuantPaperOrderMapper orderMapper;
    @Mock private QuantPaperFillMapper fillMapper;

    private PaperTradingService service;

    @BeforeEach
    void setUp() {
        QuantPaperProperties properties = new QuantPaperProperties();
        properties.setFeeBps(new BigDecimal("9"));
        properties.setSlippageBps(new BigDecimal("5"));
        properties.setInitialCashCny(new BigDecimal("100000"));
        properties.setMinimumOrderCny(new BigDecimal("100"));
        properties.setStockLotSize(100);
        properties.setFundQuantityScale(4);
        properties.setMaximumVolumeParticipation(new BigDecimal("0.10"));
        properties.setLimitLockMinimumMoveRatio(new BigDecimal("0.095"));
        properties.setStockExecutionDelayDays(1);
        properties.setFundExecutionDelayDays(1);
        properties.setQdiiExecutionDelayDays(2);
        service = new PaperTradingService(
                accountMapper, cashMapper, positionMapper, productMapper, quoteMapper,
                orderMapper, fillMapper, properties);
    }

    @Test
    void sameSubmittedOrderCanOnlyBeClaimedAndFilledOnce() {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(20L);
        product.setProductType("STOCK");
        ProductDailyQuote quote = new ProductDailyQuote();
        quote.setProductId(20L);
        quote.setTradeDate(LocalDate.of(2026, 7, 22));
        quote.setClosePrice(new BigDecimal("10"));
        InvestmentCashBalance cash = new InvestmentCashBalance();
        cash.setId(30L);
        cash.setBalance(new BigDecimal("100000"));

        when(productMapper.selectById(20L)).thenReturn(product);
        when(quoteMapper.selectOne(any())).thenReturn(quote);
        when(cashMapper.selectOne(any())).thenReturn(cash);
        when(positionMapper.selectOne(any())).thenReturn(null);
        when(accountMapper.lockActivePaperAccount(2L)).thenReturn(1);
        when(orderMapper.claimSubmitted(10L)).thenReturn(1, 0);

        service.execute(submittedOrder());
        service.execute(submittedOrder());

        verify(fillMapper, times(1)).insert(any(QuantPaperFill.class));
        verify(cashMapper, times(1)).updateById(any(InvestmentCashBalance.class));
        verify(accountMapper, times(1)).lockActivePaperAccount(2L);
    }

    @Test
    void concurrentPaperAccountCreationReusesTheWinningAccount() {
        InvestmentAccount existing = new InvestmentAccount();
        existing.setId(99L);
        existing.setUserId(1L);
        existing.setAccountType("PAPER");
        InvestmentProduct product = new InvestmentProduct();
        product.setId(20L);
        product.setProductType("STOCK");
        QuantPrediction prediction = new QuantPrediction();
        prediction.setId(40L);
        prediction.setStrategyVersion("strategy-v1");

        when(accountMapper.selectOne(any())).thenReturn(null, existing);
        doThrow(new DuplicateKeyException("paper account already created"))
                .when(accountMapper).insert(any(InvestmentAccount.class));
        when(orderMapper.selectCount(any())).thenReturn(1L);

        service.queueValidatedPrediction(1L, product, prediction, "VALIDATED");

        verify(cashMapper, never()).insert(any(InvestmentCashBalance.class));
    }

    @Test
    void concurrentPredictionOrderConflictIsIdempotent() {
        InvestmentAccount account = new InvestmentAccount();
        account.setId(99L);
        account.setUserId(1L);
        account.setAccountType("PAPER");
        InvestmentProduct product = new InvestmentProduct();
        product.setId(20L);
        product.setProductType("STOCK");
        ProductDailyQuote quote = new ProductDailyQuote();
        quote.setTradeDate(LocalDate.of(2026, 7, 22));
        quote.setClosePrice(new BigDecimal("10"));
        InvestmentCashBalance cash = new InvestmentCashBalance();
        cash.setBalance(new BigDecimal("100000"));
        QuantPrediction prediction = new QuantPrediction();
        prediction.setId(40L);
        prediction.setStrategyVersion("strategy-v1");
        prediction.setAction("ADD");
        prediction.setTargetWeight(new BigDecimal("0.10"));

        when(accountMapper.selectOne(any())).thenReturn(account);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(quoteMapper.selectOne(any())).thenReturn(quote);
        when(cashMapper.selectOne(any())).thenReturn(cash);
        when(positionMapper.selectOne(any())).thenReturn(null);
        when(positionMapper.selectList(any())).thenReturn(List.of());
        doThrow(new DuplicateKeyException("prediction order already created"))
                .when(orderMapper).insert(any(QuantPaperOrder.class));

        assertDoesNotThrow(() -> service.queueValidatedPrediction(1L, product, prediction, "VALIDATED"));
    }

    @Test
    void paperVerifiedQdiiPredictionQueuesUnknownNavOrder() {
        InvestmentAccount account = new InvestmentAccount();
        account.setId(99L);
        account.setUserId(1L);
        account.setAccountType("PAPER");
        InvestmentProduct product = new InvestmentProduct();
        product.setId(20L);
        product.setProductType("MUTUAL_FUND");
        ProductDailyQuote quote = new ProductDailyQuote();
        quote.setTradeDate(LocalDate.of(2026, 7, 22));
        quote.setClosePrice(new BigDecimal("1.25"));
        InvestmentCashBalance cash = new InvestmentCashBalance();
        cash.setBalance(new BigDecimal("100000"));
        QuantPrediction prediction = new QuantPrediction();
        prediction.setId(40L);
        prediction.setStrategyVersion("strategy-v1");
        prediction.setModelFamily("QDII_INDEX_FUND");
        prediction.setAction("ADD");
        prediction.setTargetWeight(new BigDecimal("0.10"));

        when(accountMapper.selectOne(any())).thenReturn(account);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(quoteMapper.selectOne(any())).thenReturn(quote);
        when(cashMapper.selectOne(any())).thenReturn(cash);
        when(positionMapper.selectOne(any())).thenReturn(null);
        when(positionMapper.selectList(any())).thenReturn(List.of());

        service.queueValidatedPrediction(1L, product, prediction, "PAPER_VERIFIED");

        ArgumentCaptor<QuantPaperOrder> orderCaptor =
                ArgumentCaptor.forClass(QuantPaperOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getOrderType()).isEqualTo("QDII_UNKNOWN_NAV");
    }

    @Test
    void paperTradingTransactionsReadCommittedStateAfterAccountLock() throws Exception {
        Transactional execution = PaperTradingService.class
                .getMethod("execute", QuantPaperOrder.class)
                .getAnnotation(Transactional.class);
        Transactional accountCreation = PaperTradingService.class
                .getMethod("queueValidatedPrediction", Long.class, InvestmentProduct.class,
                        QuantPrediction.class, String.class)
                .getAnnotation(Transactional.class);

        org.assertj.core.api.Assertions.assertThat(execution.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        org.assertj.core.api.Assertions.assertThat(accountCreation.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    private static QuantPaperOrder submittedOrder() {
        QuantPaperOrder order = new QuantPaperOrder();
        order.setId(10L);
        order.setUserId(1L);
        order.setAccountId(2L);
        order.setProductId(20L);
        order.setSide("BUY");
        order.setQuantity(new BigDecimal("100"));
        order.setStatus("SUBMITTED");
        order.setSubmittedAt(LocalDateTime.of(2026, 7, 20, 10, 0));
        return order;
    }
}
