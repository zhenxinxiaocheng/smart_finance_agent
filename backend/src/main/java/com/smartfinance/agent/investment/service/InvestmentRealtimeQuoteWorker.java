package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Slf4j
@Component
@ConditionalOnProperty(name = "investment.realtime.enabled", havingValue = "true", matchIfMissing = true)
public class InvestmentRealtimeQuoteWorker {

    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final InvestmentAssetService assetService;
    private final ChinaTradingCalendarService tradingCalendar;
    private final InvestmentRuntimeProperties runtimeProperties;

    public InvestmentRealtimeQuoteWorker(InvestmentAssetMapper assetMapper,
                                         InvestmentProductMapper productMapper,
                                         InvestmentAssetService assetService,
                                         ChinaTradingCalendarService tradingCalendar,
                                         InvestmentRuntimeProperties runtimeProperties) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.assetService = assetService;
        this.tradingCalendar = tradingCalendar;
        this.runtimeProperties = runtimeProperties;
    }

    @Scheduled(fixedDelayString = "${investment.runtime.market.stock-refresh-interval-ms}")
    public void refreshStockAssets() {
        refreshStockAssets(LocalDateTime.now(runtimeProperties.getMarket().getZone()));
    }

    public void refreshStockAssets(LocalDateTime now) {
        if (!tradingCalendar.isTradingDay(now.toLocalDate()) || !isTradingTime(now)) return;
        for (InvestmentAsset asset : assetMapper.selectList(
                new LambdaQueryWrapper<InvestmentAsset>().orderByAsc(InvestmentAsset::getId))) {
            InvestmentProduct product = productMapper.selectById(asset.getProductId());
            if (product == null || !"STOCK".equals(product.getProductType())) continue;
            try {
                assetService.sync(asset.getUserId(), asset.getId());
            } catch (RuntimeException exception) {
                log.warn("Realtime quote refresh failed: assetId={}, code={}, error={}",
                        asset.getId(), product.getCode(), exception.getMessage());
            }
        }
    }

    boolean isTradingTime(LocalDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        return !time.isBefore(runtimeProperties.getMarket().getStockRefreshStart())
                && !time.isAfter(runtimeProperties.getMarket().getStockRefreshEnd());
    }
}
