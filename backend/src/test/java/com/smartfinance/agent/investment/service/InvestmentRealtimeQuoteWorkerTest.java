package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvestmentRealtimeQuoteWorkerTest {

    @Test
    void refreshStockAssets_shouldOnlySyncStocksDuringTradingHours() {
        InvestmentAssetMapper assetMapper = mock(InvestmentAssetMapper.class);
        InvestmentProductMapper productMapper = mock(InvestmentProductMapper.class);
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        ChinaTradingCalendarService tradingCalendar = tradingCalendar(true);
        InvestmentRealtimeQuoteWorker worker = new InvestmentRealtimeQuoteWorker(
                assetMapper, productMapper, assetService, tradingCalendar, runtimeProperties());
        InvestmentAsset stock = asset(11L, 2L, 101L);
        InvestmentAsset fund = asset(12L, 2L, 102L);
        when(assetMapper.selectList(any(Wrapper.class))).thenReturn(List.of(stock, fund));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "STOCK"));
        when(productMapper.selectById(102L)).thenReturn(product(102L, "MUTUAL_FUND"));

        worker.refreshStockAssets(LocalDateTime.of(2026, 7, 13, 10, 0));

        verify(assetService).refresh(2L, 11L, false);
        verify(assetService, never()).refresh(2L, 12L, false);
        verify(assetService, never()).sync(anyLong(), anyLong());
    }

    @Test
    void refreshStockAssets_shouldKeepRefreshingDuringLunchBreak() {
        InvestmentAssetMapper assetMapper = mock(InvestmentAssetMapper.class);
        InvestmentProductMapper productMapper = mock(InvestmentProductMapper.class);
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        ChinaTradingCalendarService tradingCalendar = tradingCalendar(true);
        InvestmentRealtimeQuoteWorker worker = new InvestmentRealtimeQuoteWorker(
                assetMapper, productMapper, assetService, tradingCalendar, runtimeProperties());

        InvestmentAsset stock = asset(11L, 2L, 101L);
        when(assetMapper.selectList(any(Wrapper.class))).thenReturn(List.of(stock));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "STOCK"));

        worker.refreshStockAssets(LocalDateTime.of(2026, 7, 13, 12, 0));

        verify(assetService).refresh(2L, 11L, false);
    }

    @Test
    void refreshStockAssets_shouldSkipOfficialMarketHoliday() {
        InvestmentAssetMapper assetMapper = mock(InvestmentAssetMapper.class);
        InvestmentProductMapper productMapper = mock(InvestmentProductMapper.class);
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        ChinaTradingCalendarService tradingCalendar = tradingCalendar(false);
        InvestmentRealtimeQuoteWorker worker = new InvestmentRealtimeQuoteWorker(
                assetMapper, productMapper, assetService, tradingCalendar, runtimeProperties());
        InvestmentAsset stock = asset(11L, 2L, 101L);
        when(assetMapper.selectList(any(Wrapper.class))).thenReturn(List.of(stock));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "STOCK"));

        worker.refreshStockAssets(LocalDateTime.of(2026, 10, 1, 10, 0));

        verifyNoInteractions(assetService);
    }

    @Test
    void configuredStockRefreshWindowShouldControlRuntimeDecision() {
        InvestmentRuntimeProperties properties = runtimeProperties();
        properties.getMarket().setStockRefreshStart(LocalTime.of(10, 30));
        InvestmentRealtimeQuoteWorker worker = new InvestmentRealtimeQuoteWorker(
                mock(InvestmentAssetMapper.class), mock(InvestmentProductMapper.class),
                mock(InvestmentAssetService.class), tradingCalendar(true), properties);

        org.assertj.core.api.Assertions.assertThat(worker.isTradingTime(
                LocalDateTime.of(2026, 7, 13, 10, 0))).isFalse();
        org.assertj.core.api.Assertions.assertThat(worker.isTradingTime(
                LocalDateTime.of(2026, 7, 13, 11, 0))).isTrue();
    }

    private static InvestmentRuntimeProperties runtimeProperties() {
        InvestmentRuntimeProperties properties = new InvestmentRuntimeProperties();
        properties.getMarket().setStockRefreshStart(LocalTime.of(9, 15));
        properties.getMarket().setStockRefreshEnd(LocalTime.of(15, 0));
        return properties;
    }

    private static ChinaTradingCalendarService tradingCalendar(boolean tradingDay) {
        ChinaTradingCalendarService calendar = mock(ChinaTradingCalendarService.class);
        when(calendar.isTradingDay(any())).thenReturn(tradingDay);
        return calendar;
    }

    private static InvestmentAsset asset(Long id, Long userId, Long productId) {
        InvestmentAsset asset = new InvestmentAsset();
        asset.setId(id);
        asset.setUserId(userId);
        asset.setProductId(productId);
        return asset;
    }

    private static InvestmentProduct product(Long id, String type) {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(id);
        product.setProductType(type);
        return product;
    }
}
