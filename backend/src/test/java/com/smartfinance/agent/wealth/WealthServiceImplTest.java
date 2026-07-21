package com.smartfinance.agent.wealth;

import com.smartfinance.agent.investment.dto.InvestmentOverviewResponse;
import com.smartfinance.agent.investment.dto.InvestmentPositionView;
import com.smartfinance.agent.investment.entity.InvestmentCashLedger;
import com.smartfinance.agent.investment.mapper.InvestmentCashLedgerMapper;
import com.smartfinance.agent.investment.service.InvestmentService;
import com.smartfinance.agent.mapper.TransactionMapper;
import com.smartfinance.agent.wealth.entity.WealthBaseline;
import com.smartfinance.agent.wealth.mapper.WealthBaselineMapper;
import com.smartfinance.agent.wealth.service.WealthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WealthServiceImplTest {

    @Mock
    private WealthBaselineMapper baselineMapper;
    @Mock
    private TransactionMapper transactionMapper;
    @Mock
    private InvestmentCashLedgerMapper cashLedgerMapper;
    @Mock
    private InvestmentService investmentService;

    private WealthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WealthServiceImpl(baselineMapper, transactionMapper, cashLedgerMapper, investmentService);
        when(investmentService.overview(7L)).thenReturn(investmentOverview());
    }

    @Test
    void overview_shouldKeepTotalAssetsEmptyUntilCashBaselineIsInitialized() {
        when(baselineMapper.selectOne(any())).thenReturn(null);

        var result = service.overview(7L);

        assertThat(result.isInitialized()).isFalse();
        assertThat(result.getTotalAssets()).isNull();
        assertThat(result.getDailyCash()).isNull();
        assertThat(result.getInvestmentTotal()).isEqualByComparingTo("25000");
    }

    @Test
    void setBaseline_shouldStoreEnteredCashWithoutDeductingInvestments() {
        when(baselineMapper.selectOne(any())).thenReturn(null);

        service.setBaseline(7L, new BigDecimal("80000"));

        ArgumentCaptor<WealthBaseline> captor = ArgumentCaptor.forClass(WealthBaseline.class);
        verify(baselineMapper).insert(captor.capture());
        assertThat(captor.getValue().getBaselineNonInvestmentBalance()).isEqualByComparingTo("80000");
        assertThat(captor.getValue().getEnteredTotalAssets()).isEqualByComparingTo("80000");
    }

    @Test
    void overview_shouldCombineCashFlowsAndCurrentInvestmentValue() {
        WealthBaseline baseline = new WealthBaseline();
        baseline.setUserId(7L);
        baseline.setEnteredTotalAssets(new BigDecimal("80000"));
        baseline.setBaselineNonInvestmentBalance(new BigDecimal("80000"));
        baseline.setBaselineAt(LocalDateTime.of(2026, 7, 1, 0, 0));
        when(baselineMapper.selectOne(any())).thenReturn(baseline);
        when(transactionMapper.sumByUserAndTypeCreatedAfter(eq(7L), eq("INCOME"), any()))
                .thenReturn(new BigDecimal("12000"));
        when(transactionMapper.sumByUserAndTypeCreatedAfter(eq(7L), eq("EXPENSE"), any()))
                .thenReturn(new BigDecimal("5000"));
        InvestmentCashLedger transfer = new InvestmentCashLedger();
        transfer.setAmount(new BigDecimal("10000"));
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of(transfer));

        var result = service.overview(7L);

        assertThat(result.getDailyCash()).isEqualByComparingTo("77000");
        assertThat(result.getInvestmentTotal()).isEqualByComparingTo("25000");
        assertThat(result.getTotalAssets()).isEqualByComparingTo("102000");
        assertThat(result.getCashBaseline()).isEqualByComparingTo("80000");
        assertThat(result.getCashBaselineAt()).isEqualTo(baseline.getBaselineAt());
    }

    private InvestmentOverviewResponse investmentOverview() {
        InvestmentPositionView position = new InvestmentPositionView();
        position.setMarketValueCny(new BigDecimal("22000"));
        InvestmentOverviewResponse response = new InvestmentOverviewResponse();
        response.setTotalAssetCny(new BigDecimal("25000"));
        response.setPositions(List.of(position));
        response.setCashBalances(List.of(Map.of("currency", "CNY", "balance", new BigDecimal("3000"))));
        return response;
    }
}
