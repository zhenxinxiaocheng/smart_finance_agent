package com.smartfinance.agent.investment.controller;

import com.smartfinance.agent.investment.dto.InvestmentAssetCreateRequest;
import com.smartfinance.agent.investment.dto.InvestmentAssetView;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import com.smartfinance.agent.investment.dto.HorizonProfileRequest;
import com.smartfinance.agent.investment.dto.HorizonSettingRequest;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.InvestmentAnalysisService;
import com.smartfinance.agent.investment.service.InvestmentAssetService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InvestmentAssetControllerTest {

    @Test
    void create_shouldUseAuthenticatedUserId() {
        InvestmentAssetService service = mock(InvestmentAssetService.class);
        InvestmentAssetCreateRequest request = new InvestmentAssetCreateRequest();
        request.setProductType("STOCK");
        request.setCode("600519");
        InvestmentAssetView expected = new InvestmentAssetView();
        expected.setName("贵州茅台");
        when(service.create(7L, request)).thenReturn(expected);

        InvestmentAssetController controller = new InvestmentAssetController(service);

        assertThat(controller.create(7L, request).getData()).isSameAs(expected);
    }

    @Test
    void resolveAndDelete_shouldDelegateWithoutDatabaseAccessInController() {
        InvestmentAssetService service = mock(InvestmentAssetService.class);
        var expected = new AnalysisServiceClient.ResolvedProduct(
                "STOCK", "600519", "贵州茅台", "SSE", "CNY", "AKSHARE", null, null, List.of());
        when(service.resolve(7L, "STOCK", "600519")).thenReturn(expected);
        InvestmentAssetController controller = new InvestmentAssetController(service);

        assertThat(controller.resolve(7L, new InvestmentAssetController.ResolveRequest("STOCK", "600519")).getData())
                .isSameAs(expected);
        controller.delete(7L, 11L);

        verify(service).delete(7L, 11L);
    }

    @Test
    void detailAnalysisAndPreference_shouldDelegateWithAuthenticatedUser() {
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        InvestmentAnalysisService analysisService = mock(InvestmentAnalysisService.class);
        InvestmentAssetController controller = new InvestmentAssetController(assetService, analysisService);
        InvestmentAssetDetailResponse expected = new InvestmentAssetDetailResponse();
        HorizonProfileRequest preference = new HorizonProfileRequest(List.of(
                new HorizonSettingRequest("WAVE", "波段", 10, 7, 45, true)
        ));
        when(analysisService.detail(7L, 11L)).thenReturn(expected);
        when(analysisService.updatePreference(7L, 11L, preference)).thenReturn(expected);
        when(analysisService.clearPreference(7L, 11L)).thenReturn(expected);

        assertThat(controller.detail(7L, 11L).getData()).isSameAs(expected);
        assertThat(controller.updateAnalysisPreference(7L, 11L, preference).getData()).isSameAs(expected);
        assertThat(controller.clearAnalysisPreference(7L, 11L).getData()).isSameAs(expected);
        verify(analysisService).detail(7L, 11L);
        verify(analysisService).updatePreference(7L, 11L, preference);
        verify(analysisService).clearPreference(7L, 11L);
    }
}
