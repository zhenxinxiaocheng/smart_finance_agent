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
    void syncHistoryRequirement_shouldComeFromTheResolvedGlobalProfile() {
        InvestmentHorizonService horizonService = mock(InvestmentHorizonService.class);
        when(horizonService.resolve(7L, null)).thenReturn(new ResolvedHorizonProfile(
                "template:v1|global:3|asset:0", "v1",
                List.of(new HorizonSetting("POSITION", "配置", 10, 80, 900, true, "GLOBAL")),
                List.of()));
        InvestmentSyncWorker worker = new InvestmentSyncWorker(
                mock(InvestmentSyncBatchMapper.class), mock(InvestmentPositionMapper.class),
                mock(InvestmentProductMapper.class), mock(ProductDailyQuoteService.class),
                mock(DailyExchangeRateMapper.class),
                mock(AnalysisServiceClient.class), mock(InvestmentDataQualityService.class),
                mock(InvestmentDataJobService.class), horizonService,
                horizonProperties(2500), runtimeProperties(14));

        int requiredHistoryDays = worker.requiredHistoryDays(7L);

        assertThat(requiredHistoryDays).isEqualTo(2500);
        verify(horizonService).resolve(7L, null);
    }

    @Test
    void fxLookbackShouldComeFromRuntimeConfiguration() {
        InvestmentSyncWorker worker = new InvestmentSyncWorker(
                mock(InvestmentSyncBatchMapper.class), mock(InvestmentPositionMapper.class),
                mock(InvestmentProductMapper.class), mock(ProductDailyQuoteService.class),
                mock(DailyExchangeRateMapper.class),
                mock(AnalysisServiceClient.class), mock(InvestmentDataQualityService.class),
                mock(InvestmentDataJobService.class), mock(InvestmentHorizonService.class),
                horizonProperties(2500), runtimeProperties(21));

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
        properties.getSync().setBatchLimit(5);
        properties.getSync().setErrorMessageMaxLength(500);
        properties.getSync().setFxLookbackCalendarDays(fxLookbackDays);
        properties.getDataQuality().setStockAdjustType("QFQ");
        properties.getDataQuality().setFundAdjustType("NONE");
        return properties;
    }

}
