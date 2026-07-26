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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvestmentFundQuoteWorkerTest {

    @Test
    void refreshFundAssets_shouldOnlySyncFundsDuringRefreshWindow() {
        InvestmentAssetMapper assetMapper = mock(InvestmentAssetMapper.class);
        InvestmentProductMapper productMapper = mock(InvestmentProductMapper.class);
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        InvestmentFundQuoteWorker worker = new InvestmentFundQuoteWorker(
                assetMapper, productMapper, assetService, runtimeProperties());
        InvestmentAsset stock = asset(11L, 2L, 101L);
        InvestmentAsset fund = asset(12L, 2L, 102L);
        when(assetMapper.selectList(any(Wrapper.class))).thenReturn(List.of(stock, fund));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "STOCK"));
        when(productMapper.selectById(102L)).thenReturn(product(102L, "MUTUAL_FUND"));

        worker.refreshFundAssets(LocalDateTime.of(2026, 7, 16, 10, 0));

        verify(assetService, never()).refresh(2L, 11L, false);
        verify(assetService).refresh(2L, 12L, false);
        verify(assetService, never()).sync(anyLong(), anyLong());
    }

    @Test
    void refreshFundAssets_shouldContinueAfterOneFundFails() {
        InvestmentAssetMapper assetMapper = mock(InvestmentAssetMapper.class);
        InvestmentProductMapper productMapper = mock(InvestmentProductMapper.class);
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        InvestmentFundQuoteWorker worker = new InvestmentFundQuoteWorker(
                assetMapper, productMapper, assetService, runtimeProperties());
        InvestmentAsset first = asset(11L, 2L, 101L);
        InvestmentAsset second = asset(12L, 3L, 102L);
        when(assetMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(productMapper.selectById(101L)).thenReturn(product(101L, "MUTUAL_FUND"));
        when(productMapper.selectById(102L)).thenReturn(product(102L, "MUTUAL_FUND"));
        doThrow(new IllegalStateException("provider unavailable"))
                .when(assetService).refresh(2L, 11L, false);

        worker.refreshFundAssets(LocalDateTime.of(2026, 7, 16, 20, 0));

        verify(assetService).refresh(2L, 11L, false);
        verify(assetService).refresh(3L, 12L, false);
    }

    @Test
    void refreshFundAssets_shouldSkipWeekendsAndOvernight() {
        InvestmentAssetMapper assetMapper = mock(InvestmentAssetMapper.class);
        InvestmentFundQuoteWorker worker = new InvestmentFundQuoteWorker(
                assetMapper, mock(InvestmentProductMapper.class), mock(InvestmentAssetService.class),
                runtimeProperties());

        worker.refreshFundAssets(LocalDateTime.of(2026, 7, 18, 10, 0));
        worker.refreshFundAssets(LocalDateTime.of(2026, 7, 16, 2, 0));

        verifyNoInteractions(assetMapper);
        assertThat(worker.isRefreshTime(
                LocalDateTime.of(2026, 7, 16, 23, 0))).isTrue();
        assertThat(worker.isRefreshTime(
                LocalDateTime.of(2026, 7, 16, 23, 1))).isFalse();
    }

    @Test
    void configuredFundRefreshWindowShouldControlRuntimeDecision() {
        InvestmentRuntimeProperties properties = runtimeProperties();
        properties.getMarket().setFundRefreshEnd(LocalTime.of(18, 0));
        InvestmentFundQuoteWorker worker = new InvestmentFundQuoteWorker(
                mock(InvestmentAssetMapper.class), mock(InvestmentProductMapper.class),
                mock(InvestmentAssetService.class), properties);

        assertThat(worker.isRefreshTime(LocalDateTime.of(2026, 7, 16, 18, 0))).isTrue();
        assertThat(worker.isRefreshTime(LocalDateTime.of(2026, 7, 16, 18, 1))).isFalse();
    }

    private static InvestmentRuntimeProperties runtimeProperties() {
        InvestmentRuntimeProperties properties = new InvestmentRuntimeProperties();
        properties.getMarket().setFundRefreshStart(LocalTime.of(8, 0));
        properties.getMarket().setFundRefreshEnd(LocalTime.of(23, 0));
        return properties;
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
