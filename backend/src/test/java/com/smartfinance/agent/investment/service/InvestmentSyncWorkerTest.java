package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvestmentSyncWorkerTest {

    @Test
    void normalEmptyQueryKeepsExistingPositionAndDoesNotCountAsSourceFailure() {
        var batches = mock(InvestmentSyncBatchMapper.class); var positions = mock(InvestmentPositionMapper.class);
        var products = mock(InvestmentProductMapper.class); var quotes = mock(ProductDailyQuoteMapper.class);
        var history = mock(InvestmentHistoryPreparationService.class);
        var position = new com.smartfinance.agent.investment.entity.InvestmentPosition();
        position.setProductId(8L);position.setQuantity(java.math.BigDecimal.ONE);position.setCostAmount(java.math.BigDecimal.TEN);
        position.setLatestPrice(java.math.BigDecimal.TEN);
        var product = new InvestmentProduct();product.setId(8L);product.setProductType("STOCK");product.setMarket("NASDAQ");product.setCurrency("CNY");
        when(positions.selectList(any())).thenReturn(List.of(position));when(products.selectById(8L)).thenReturn(product);
        when(history.prepareProduct(product,"STOCK_HISTORY",false)).thenReturn(
            new InvestmentHistoryPreparationService.PreparationResult(1,null,null,null,false,null,true,java.time.LocalDate.now()));
        var last = new ProductDailyQuote(); last.setClosePrice(java.math.BigDecimal.TEN);
        last.setTradeDate(java.time.LocalDate.of(2026,10,8)); last.setSource("SINA");
        when(quotes.selectOne(any())).thenReturn(last);
        var worker = new InvestmentSyncWorker(batches,positions,products,quotes,mock(DailyExchangeRateMapper.class),
                mock(AnalysisServiceClient.class),history,mock(InvestmentDataJobService.class),runtimeProperties(14));
        var batch = new com.smartfinance.agent.investment.entity.InvestmentSyncBatch();batch.setUserId(7L);
        worker.process(batch);
        assertThat(batch.getRowsFailed()).isZero();assertThat(batch.getRowsSuccess()).isEqualTo(1);
        assertThat(position.getLatestPrice()).isEqualByComparingTo("10");
        verify(history).prepareProduct(product,"STOCK_HISTORY",false);
        verify(quotes,never()).upsertHistory(any());
    }

    @Test
    void fxLookbackShouldComeFromRuntimeConfiguration() {
        InvestmentSyncWorker worker = new InvestmentSyncWorker(
                mock(InvestmentSyncBatchMapper.class), mock(InvestmentPositionMapper.class),
                mock(InvestmentProductMapper.class), mock(ProductDailyQuoteMapper.class),
                mock(DailyExchangeRateMapper.class),
                mock(AnalysisServiceClient.class), mock(InvestmentHistoryPreparationService.class),
                mock(InvestmentDataJobService.class), runtimeProperties(21));

        assertThat(worker.fxStartDate(java.time.LocalDate.of(2026, 7, 16)))
                .isEqualTo(java.time.LocalDate.of(2026, 6, 25));
    }

    static InvestmentHorizonProperties horizonProperties(int maximumHistoryDays) {
        InvestmentHorizonProperties properties = new InvestmentHorizonProperties();
        properties.setMaxHistoryTradingDays(maximumHistoryDays);
        properties.setHistoryMultiplier(3);
        properties.setMinimumHistoryTradingDays(20);
        properties.setCalendarDaysPerYear(365);
        properties.setTradingDaysPerYear(240);
        properties.setCalendarBufferDays(30);
        return properties;
    }

    static InvestmentRuntimeProperties runtimeProperties(int fxLookbackDays) {
        InvestmentRuntimeProperties properties = new InvestmentRuntimeProperties();
        properties.getMarket().setZone(java.time.ZoneId.of("Asia/Shanghai"));
        properties.getSync().setBatchLimit(5);
        properties.getSync().setErrorMessageMaxLength(500);
        properties.getSync().setFxLookbackCalendarDays(fxLookbackDays);
        properties.getDataQuality().setStockAdjustType("QFQ");
        properties.getDataQuality().setFundAdjustType("NONE");
        return properties;
    }

}
