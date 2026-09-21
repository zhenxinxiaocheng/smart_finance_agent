package com.smartfinance.agent.investment.controller;

import com.smartfinance.agent.investment.dto.InvestmentAssetCreateRequest;
import com.smartfinance.agent.investment.dto.InvestmentAssetView;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import com.smartfinance.agent.investment.dto.HorizonProfileRequest;
import com.smartfinance.agent.investment.dto.HorizonSettingRequest;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.InvestmentAnalysisService;
import com.smartfinance.agent.investment.service.InvestmentAssetService;
import com.smartfinance.agent.investment.service.InvestmentDataJobService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void refresh_shouldDelegateForceFlagForAuthenticatedUser() {
        InvestmentAssetService service = mock(InvestmentAssetService.class);
        InvestmentAssetView expected = new InvestmentAssetView();
        when(service.refreshAll(7L, true)).thenReturn(List.of(expected));
        InvestmentAssetController controller = new InvestmentAssetController(service);

        assertThat(controller.refresh(7L, true).getData()).containsExactly(expected);
        verify(service).refreshAll(7L, true);
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

    @Test
    void historyJob_shouldVerifyAssetOwnershipBeforeReturningSafeStatus() {
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        InvestmentAnalysisService analysisService = mock(InvestmentAnalysisService.class);
        InvestmentDataJobService jobService = mock(InvestmentDataJobService.class);
        InvestmentAssetView asset = asset(11L, 21L, "STOCK");
        Map<String, Object> status = Map.of("status", "RUNNING", "recordCount", 3);
        when(assetService.get(7L, 11L)).thenReturn(asset);
        when(jobService.statusForAsset(7L, 11L)).thenReturn(status);
        InvestmentAssetController controller = new InvestmentAssetController(
                assetService, analysisService, jobService);

        assertThat(controller.historyJob(7L, 11L).getData()).isEqualTo(status);
        verify(assetService).get(7L, 11L);
        verify(jobService).statusForAsset(7L, 11L);
    }

    @Test
    void historyJob_shouldRejectAnotherUserBeforeJobLookup() {
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        InvestmentAnalysisService analysisService = mock(InvestmentAnalysisService.class);
        InvestmentDataJobService jobService = mock(InvestmentDataJobService.class);
        when(assetService.get(8L, 11L)).thenThrow(new IllegalArgumentException("资产不存在"));
        InvestmentAssetController controller = new InvestmentAssetController(
                assetService, analysisService, jobService);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.historyJob(8L, 11L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jobService);
    }

    @Test
    void dataQualityRefresh_shouldOnlyForceQueueAndReturnReadOnlyDetail() {
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        InvestmentAnalysisService analysisService = mock(InvestmentAnalysisService.class);
        InvestmentDataJobService jobService = mock(InvestmentDataJobService.class);
        InvestmentAssetView asset = asset(11L, 21L, "MUTUAL_FUND");
        InvestmentAssetDetailResponse detail = new InvestmentAssetDetailResponse();
        when(assetService.get(7L, 11L)).thenReturn(asset);
        when(analysisService.queueDataRefresh(7L, 11L)).thenReturn(detail);
        InvestmentAssetController controller = new InvestmentAssetController(
                assetService, analysisService, jobService);

        assertThat(controller.refreshDataQuality(7L, 11L).getData()).isSameAs(detail);
        verifyNoInteractions(jobService);
        verify(analysisService).queueDataRefresh(7L, 11L);
        verify(analysisService, never()).retryData(any(), any());
        verify(analysisService, never()).refresh(any(), any());
    }

    private static InvestmentAssetView asset(Long id, Long productId, String productType) {
        InvestmentAssetView asset = new InvestmentAssetView();
        asset.setId(id);
        asset.setProductId(productId);
        asset.setProductType(productType);
        return asset;
    }
}
