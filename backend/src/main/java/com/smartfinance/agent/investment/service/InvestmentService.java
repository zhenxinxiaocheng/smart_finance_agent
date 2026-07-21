package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.dto.*;
import com.smartfinance.agent.investment.entity.InvestmentAccount;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.InvestmentTransaction;
import com.smartfinance.agent.investment.entity.InvestmentPlan;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface InvestmentService {
    InvestmentOverviewResponse overview(Long userId);
    List<InvestmentAccount> listAccounts(Long userId);
    InvestmentAccount createAccount(Long userId, InvestmentAccountRequest request);
    List<InvestmentProduct> searchProducts(String keyword, String market, String productType);
    List<InvestmentPositionView> listPositions(Long userId, Long accountId, String market, String productType);
    List<InvestmentTransaction> listTransactions(Long userId, Long accountId, int limit);
    InvestmentTransaction addTransaction(Long userId, InvestmentTransactionRequest request);
    InvestmentTransaction reverseTransaction(Long userId, Long transactionId, String note);
    InvestmentImportPreviewResponse previewImport(Long userId, MultipartFile file);
    Map<String, Object> commitImport(Long userId, InvestmentImportCommitRequest request);
    Map<String, Object> analysis(Long userId);
    List<Map<String, Object>> dataQuality(Long userId);
    List<Map<String, Object>> recommendations(Long userId);
    Map<String, Object> requestSync(Long userId);
    List<InvestmentPlan> listPlans(Long userId);
    InvestmentPlan createPlan(Long userId, InvestmentPlanRequest request);
    InvestmentPlan setPlanEnabled(Long userId, Long planId, boolean enabled);
}
