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
@ConditionalOnProperty(name = "investment.fund-sync.enabled", havingValue = "true", matchIfMissing = true)
public class InvestmentFundQuoteWorker {

    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final InvestmentAssetService assetService;
    private final InvestmentRuntimeProperties runtimeProperties;

    public InvestmentFundQuoteWorker(InvestmentAssetMapper assetMapper,
                                     InvestmentProductMapper productMapper,
                                     InvestmentAssetService assetService,
                                     InvestmentRuntimeProperties runtimeProperties) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.assetService = assetService;
        this.runtimeProperties = runtimeProperties;
    }

    @Scheduled(
            initialDelayString = "${investment.runtime.market.fund-initial-delay-ms}",
            fixedDelayString = "${investment.runtime.market.fund-refresh-interval-ms}"
    )
    public void refreshFundAssets() {
        refreshFundAssets(LocalDateTime.now(runtimeProperties.getMarket().getZone()));
    }

    void refreshFundAssets(LocalDateTime now) {
        if (!isRefreshTime(now)) return;
        for (InvestmentAsset asset : assetMapper.selectList(
                new LambdaQueryWrapper<InvestmentAsset>().orderByAsc(InvestmentAsset::getId))) {
            InvestmentProduct product = productMapper.selectById(asset.getProductId());
            if (product == null || !"MUTUAL_FUND".equals(product.getProductType())) continue;
            try {
                assetService.sync(asset.getUserId(), asset.getId());
            } catch (RuntimeException exception) {
                log.warn("Fund quote refresh failed: assetId={}, code={}, error={}",
                        asset.getId(), product.getCode(), exception.getMessage());
            }
        }
    }

    boolean isRefreshTime(LocalDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        return !time.isBefore(runtimeProperties.getMarket().getFundRefreshStart())
                && !time.isAfter(runtimeProperties.getMarket().getFundRefreshEnd());
    }
}
