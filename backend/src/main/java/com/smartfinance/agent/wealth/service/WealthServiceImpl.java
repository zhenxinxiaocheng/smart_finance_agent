package com.smartfinance.agent.wealth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.dto.InvestmentOverviewResponse;
import com.smartfinance.agent.investment.dto.InvestmentPositionView;
import com.smartfinance.agent.investment.entity.InvestmentCashLedger;
import com.smartfinance.agent.investment.mapper.InvestmentCashLedgerMapper;
import com.smartfinance.agent.investment.service.InvestmentService;
import com.smartfinance.agent.mapper.TransactionMapper;
import com.smartfinance.agent.wealth.WealthCalculator;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.entity.WealthBaseline;
import com.smartfinance.agent.wealth.mapper.WealthBaselineMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class WealthServiceImpl implements WealthService {

    private final WealthBaselineMapper baselineMapper;
    private final TransactionMapper transactionMapper;
    private final InvestmentCashLedgerMapper cashLedgerMapper;
    private final InvestmentService investmentService;
    private final WealthCalculator calculator = new WealthCalculator();

    public WealthServiceImpl(WealthBaselineMapper baselineMapper,
                             TransactionMapper transactionMapper,
                             InvestmentCashLedgerMapper cashLedgerMapper,
                             InvestmentService investmentService) {
        this.baselineMapper = baselineMapper;
        this.transactionMapper = transactionMapper;
        this.cashLedgerMapper = cashLedgerMapper;
        this.investmentService = investmentService;
    }

    @Override
    public WealthOverviewResponse overview(Long userId) {
        InvestmentOverviewResponse investment = investmentService.overview(userId);
        BigDecimal cash = cnyCash(investment.getCashBalances());
        BigDecimal marketValue = investment.getPositions().stream()
                .map(InvestmentPositionView::getMarketValueCny)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        WealthBaseline baseline = findBaseline(userId);
        WealthOverviewResponse response = new WealthOverviewResponse();
        response.setInvestmentCash(cash);
        response.setHoldingMarketValue(marketValue);
        response.setInvestmentTotal(cash.add(marketValue));
        if (baseline == null) {
            response.setWarnings(List.of("请先在财务画像填写当前现金余额，以建立现金基准"));
            return response;
        }
        BigDecimal income = transactionMapper.sumByUserAndTypeCreatedAfter(userId, "INCOME", baseline.getBaselineAt());
        BigDecimal expense = transactionMapper.sumByUserAndTypeCreatedAfter(userId, "EXPENSE", baseline.getBaselineAt());
        List<InvestmentCashLedger> flows = cashLedgerMapper.selectList(new LambdaQueryWrapper<InvestmentCashLedger>()
                .eq(InvestmentCashLedger::getUserId, userId)
                .eq(InvestmentCashLedger::getExternalFlow, 1)
                .eq(InvestmentCashLedger::getCurrency, "CNY")
                .gt(InvestmentCashLedger::getCreatedAt, baseline.getBaselineAt()));
        BigDecimal netTransfer = flows.stream().map(InvestmentCashLedger::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        WealthCalculator.Result result = calculator.calculate(
                baseline.getBaselineNonInvestmentBalance(), income, expense, netTransfer, cash, marketValue);
        response.setInitialized(true);
        response.setTotalAssets(result.totalAssets());
        response.setDailyCash(result.dailyCash());
        response.setIncomeAfterBaseline(income);
        response.setExpenseAfterBaseline(expense);
        response.setNetInvestmentTransfer(netTransfer);
        response.setCashBaseline(baseline.getBaselineNonInvestmentBalance());
        response.setCashBaselineAt(baseline.getBaselineAt());
        return response;
    }

    @Override
    @Transactional
    public WealthOverviewResponse setBaseline(Long userId, BigDecimal cashBalance) {
        if (cashBalance == null || cashBalance.signum() < 0) {
            throw new IllegalArgumentException("现金余额不能小于 0");
        }
        WealthBaseline baseline = findBaseline(userId);
        if (baseline == null) {
            baseline = new WealthBaseline();
            baseline.setUserId(userId);
        }
        baseline.setEnteredTotalAssets(cashBalance);
        baseline.setBaselineNonInvestmentBalance(cashBalance);
        baseline.setBaselineAt(LocalDateTime.now());
        if (baseline.getId() == null) baselineMapper.insert(baseline); else baselineMapper.updateById(baseline);
        return overview(userId);
    }

    private WealthBaseline findBaseline(Long userId) {
        return baselineMapper.selectOne(new LambdaQueryWrapper<WealthBaseline>()
                .eq(WealthBaseline::getUserId, userId).last("LIMIT 1"));
    }

    private static BigDecimal cnyCash(List<Map<String, Object>> balances) {
        if (balances == null) return BigDecimal.ZERO;
        return balances.stream()
                .filter(item -> "CNY".equals(String.valueOf(item.get("currency"))))
                .map(item -> new BigDecimal(String.valueOf(item.getOrDefault("balance", "0"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
