package com.smartfinance.agent.investment.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.investment.dto.InvestmentAssetCreateRequest;
import com.smartfinance.agent.investment.dto.HorizonProfileRequest;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import com.smartfinance.agent.investment.dto.InvestmentAssetUpdateRequest;
import com.smartfinance.agent.investment.dto.InvestmentAssetView;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.InvestmentAssetService;
import com.smartfinance.agent.investment.service.InvestmentAnalysisService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/investment/assets")
public class InvestmentAssetController {

    private final InvestmentAssetService assetService;
    private final InvestmentAnalysisService analysisService;

    public InvestmentAssetController(InvestmentAssetService assetService) {
        this(assetService, null);
    }

    @Autowired
    public InvestmentAssetController(InvestmentAssetService assetService,
                                     InvestmentAnalysisService analysisService) {
        this.assetService = assetService;
        this.analysisService = analysisService;
    }

    @PostMapping("/resolve")
    public Result<AnalysisServiceClient.ResolvedProduct> resolve(@RequestAttribute Long userId,
                                                                  @Valid @RequestBody ResolveRequest request) {
        return Result.success(assetService.resolve(userId, request.productType(), request.code()));
    }

    @PostMapping
    public Result<InvestmentAssetView> create(@RequestAttribute Long userId,
                                               @Valid @RequestBody InvestmentAssetCreateRequest request) {
        return Result.success(assetService.create(userId, request));
    }

    @GetMapping
    public Result<List<InvestmentAssetView>> list(@RequestAttribute Long userId) {
        return Result.success(assetService.list(userId));
    }

    @GetMapping("/{id}")
    public Result<InvestmentAssetView> get(@RequestAttribute Long userId, @PathVariable Long id) {
        return Result.success(assetService.get(userId, id));
    }

    @PutMapping("/{id}")
    public Result<InvestmentAssetView> update(@RequestAttribute Long userId,
                                               @PathVariable Long id,
                                               @Valid @RequestBody InvestmentAssetUpdateRequest request) {
        return Result.success(assetService.update(userId, id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute Long userId, @PathVariable Long id) {
        assetService.delete(userId, id);
        return Result.success();
    }

    @PostMapping("/{id}/sync")
    public Result<InvestmentAssetView> sync(@RequestAttribute Long userId, @PathVariable Long id) {
        return Result.success(assetService.sync(userId, id));
    }

    @GetMapping("/{id}/detail")
    public Result<InvestmentAssetDetailResponse> detail(@RequestAttribute Long userId, @PathVariable Long id) {
        return Result.success(analysisService.detail(userId, id));
    }

    @GetMapping("/{id}/analysis")
    public Result<InvestmentAssetDetailResponse> analysis(@RequestAttribute Long userId, @PathVariable Long id) {
        return Result.success(analysisService.analysis(userId, id));
    }

    @PutMapping("/{id}/analysis-preference")
    public Result<InvestmentAssetDetailResponse> updateAnalysisPreference(
            @RequestAttribute Long userId, @PathVariable Long id,
            @Valid @RequestBody HorizonProfileRequest request) {
        return Result.success(analysisService.updatePreference(userId, id, request));
    }

    @DeleteMapping("/{id}/analysis-preference")
    public Result<InvestmentAssetDetailResponse> clearAnalysisPreference(
            @RequestAttribute Long userId, @PathVariable Long id) {
        return Result.success(analysisService.clearPreference(userId, id));
    }

    @PostMapping("/{id}/analysis/refresh")
    public Result<InvestmentAssetDetailResponse> refreshAnalysis(@RequestAttribute Long userId,
                                                                  @PathVariable Long id) {
        return Result.success(analysisService.refresh(userId, id));
    }

    @PostMapping("/{id}/data-quality/refresh")
    public Result<InvestmentAssetDetailResponse> refreshDataQuality(@RequestAttribute Long userId,
                                                                     @PathVariable Long id) {
        return Result.success(analysisService.retryData(userId, id));
    }

    public record ResolveRequest(@NotBlank String productType, @NotBlank String code) {
    }
}
