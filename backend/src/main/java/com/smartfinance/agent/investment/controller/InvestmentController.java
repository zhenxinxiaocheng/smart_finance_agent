package com.smartfinance.agent.investment.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.investment.dto.*;
import com.smartfinance.agent.investment.entity.InvestmentAccount;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.InvestmentTransaction;
import com.smartfinance.agent.investment.entity.InvestmentPlan;
import com.smartfinance.agent.investment.service.InvestmentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/investment")
public class InvestmentController {

    private final InvestmentService investmentService;

    public InvestmentController(InvestmentService investmentService) {
        this.investmentService = investmentService;
    }

    @GetMapping("/overview")
    public Result<InvestmentOverviewResponse> overview(@RequestAttribute Long userId) {
        return Result.success(investmentService.overview(userId));
    }

    @GetMapping("/accounts")
    public Result<List<InvestmentAccount>> accounts(@RequestAttribute Long userId) {
        return Result.success(investmentService.listAccounts(userId));
    }

    @PostMapping("/accounts")
    public Result<InvestmentAccount> createAccount(@RequestAttribute Long userId,
                                                    @Valid @RequestBody InvestmentAccountRequest request) {
        return Result.success(investmentService.createAccount(userId, request));
    }

    @GetMapping("/products")
    public Result<List<InvestmentProduct>> products(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String market,
                                                     @RequestParam(required = false) String productType) {
        return Result.success(investmentService.searchProducts(keyword, market, productType));
    }

    @GetMapping("/positions")
    public Result<List<InvestmentPositionView>> positions(@RequestAttribute Long userId,
                                                           @RequestParam(required = false) Long accountId,
                                                           @RequestParam(required = false) String market,
                                                           @RequestParam(required = false) String productType) {
        return Result.success(investmentService.listPositions(userId, accountId, market, productType));
    }

    @GetMapping("/transactions")
    public Result<List<InvestmentTransaction>> transactions(@RequestAttribute Long userId,
                                                              @RequestParam(required = false) Long accountId,
                                                              @RequestParam(defaultValue = "${investment.runtime.api.default-transaction-limit}") int limit) {
        return Result.success(investmentService.listTransactions(userId, accountId, limit));
    }

    @PostMapping("/transactions")
    public Result<InvestmentTransaction> addTransaction(@RequestAttribute Long userId,
                                                         @Valid @RequestBody InvestmentTransactionRequest request) {
        return Result.success(investmentService.addTransaction(userId, request));
    }

    @PostMapping("/transactions/{id}/reverse")
    public Result<InvestmentTransaction> reverseTransaction(@RequestAttribute Long userId,
                                                             @PathVariable Long id,
                                                             @RequestBody(required = false) Map<String, String> body) {
        return Result.success(investmentService.reverseTransaction(userId, id,
                body == null ? null : body.get("note")));
    }

    @PostMapping("/imports/preview")
    public Result<InvestmentImportPreviewResponse> previewImport(@RequestAttribute Long userId,
                                                                  @RequestParam("file") MultipartFile file) {
        return Result.success(investmentService.previewImport(userId, file));
    }

    @PostMapping("/imports/commit")
    public Result<Map<String, Object>> commitImport(@RequestAttribute Long userId,
                                                     @Valid @RequestBody InvestmentImportCommitRequest request) {
        return Result.success(investmentService.commitImport(userId, request));
    }

    @PostMapping("/sync")
    public Result<Map<String, Object>> sync(@RequestAttribute Long userId) {
        return Result.success(investmentService.requestSync(userId));
    }

    @GetMapping("/plans")
    public Result<List<InvestmentPlan>> plans(@RequestAttribute Long userId) {
        return Result.success(investmentService.listPlans(userId));
    }

    @PostMapping("/plans")
    public Result<InvestmentPlan> createPlan(@RequestAttribute Long userId,
                                             @Valid @RequestBody InvestmentPlanRequest request) {
        return Result.success(investmentService.createPlan(userId, request));
    }

    @PutMapping("/plans/{id}")
    public Result<InvestmentPlan> updatePlan(@RequestAttribute Long userId,
                                             @PathVariable Long id,
                                             @Valid @RequestBody InvestmentPlanRequest request) {
        return Result.success(investmentService.updatePlan(userId, id, request));
    }

    @PostMapping("/plans/{id}/enabled")
    public Result<InvestmentPlan> setPlanEnabled(@RequestAttribute Long userId,
                                                  @PathVariable Long id,
                                                  @RequestBody Map<String, Boolean> body) {
        return Result.success(investmentService.setPlanEnabled(userId, id, Boolean.TRUE.equals(body.get("enabled"))));
    }

    @DeleteMapping("/plans/{id}")
    public Result<Void> deletePlan(@RequestAttribute Long userId, @PathVariable Long id) {
        investmentService.deletePlan(userId, id);
        return Result.success();
    }
}
