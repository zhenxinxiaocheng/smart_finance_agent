package com.smartfinance.agent.service;

import com.smartfinance.agent.investment.dto.InvestmentAccountRequest;
import com.smartfinance.agent.investment.dto.InvestmentProductRequest;
import com.smartfinance.agent.investment.dto.InvestmentTransactionRequest;
import com.smartfinance.agent.investment.dto.InvestmentPlanRequest;
import com.smartfinance.agent.investment.service.InvestmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ServiceIntegrationTestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:investment_service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class InvestmentServiceIntegrationTest {

    @Autowired
    private InvestmentService investmentService;

    @Test
    void accountDepositBuyAndSell_shouldBuildCashAndPositionProjections() {
        InvestmentAccountRequest accountRequest = new InvestmentAccountRequest();
        accountRequest.setAccountName("测试账户");
        accountRequest.setAccountType("BROKER");
        accountRequest.setOpeningCash(new BigDecimal("10000"));
        var account = investmentService.createAccount(1L, accountRequest);

        InvestmentProductRequest product = new InvestmentProductRequest();
        product.setProductType("STOCK");
        product.setMarket("SSE");
        product.setCode("600519");
        product.setName("贵州茅台");
        product.setCurrency("CNY");

        InvestmentTransactionRequest buy = transaction(account.getId(), product, "BUY", "10", "500", "5");
        investmentService.addTransaction(1L, buy);
        InvestmentTransactionRequest sell = transaction(account.getId(), product, "SELL", "2", "600", "2");
        investmentService.addTransaction(1L, sell);

        var overview = investmentService.overview(1L);
        var position = overview.getPositions().get(0);
        assertThat(position.getQuantity()).isEqualByComparingTo("8");
        assertThat(position.getCostAmount()).isEqualByComparingTo("4004.0000000000");
        assertThat(position.getRealizedPnl()).isEqualByComparingTo("197.0000000000");
        assertThat(overview.getNetInvestmentCny()).isEqualByComparingTo("10000");
        assertThat(overview.getCashBalances().get(0).get("balance")).isEqualTo(new BigDecimal("6193.00000000"));
    }

    @Test
    void createPlan_shouldPersistUserOwnedRecurringPlan() {
        InvestmentAccountRequest accountRequest = new InvestmentAccountRequest();
        accountRequest.setAccountName("基金账户");
        accountRequest.setAccountType("FUND_PLATFORM");
        var account = investmentService.createAccount(7L, accountRequest);

        InvestmentProductRequest product = new InvestmentProductRequest();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("000001");
        product.setName("测试基金");
        product.setCurrency("CNY");
        InvestmentPlanRequest request = new InvestmentPlanRequest();
        request.setAccountId(account.getId());
        request.setProduct(product);
        request.setAmount(new BigDecimal("1000"));
        request.setCurrency("CNY");
        request.setFrequency("MONTHLY");
        request.setExecutionDay(10);
        request.setNextExecutionDate(LocalDate.of(2026, 8, 10));

        var plan = investmentService.createPlan(7L, request);

        assertThat(plan.getEnabled()).isEqualTo(1);
        assertThat(investmentService.listPlans(7L)).extracting("id").containsExactly(plan.getId());
        assertThat(investmentService.listPlans(8L)).isEmpty();
    }

    @Test
    void createPlan_shouldAcceptDailyFrequency() {
        InvestmentAccountRequest accountRequest = new InvestmentAccountRequest();
        accountRequest.setAccountName("每日定投账户");
        accountRequest.setAccountType("FUND_PLATFORM");
        var account = investmentService.createAccount(9L, accountRequest);

        InvestmentProductRequest product = new InvestmentProductRequest();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("010736");
        product.setName("测试每日定投基金");
        product.setCurrency("CNY");

        InvestmentPlanRequest request = new InvestmentPlanRequest();
        request.setAccountId(account.getId());
        request.setProduct(product);
        request.setAmount(new BigDecimal("10"));
        request.setCurrency("CNY");
        request.setFrequency("DAILY");
        request.setExecutionDay(1);
        request.setNextExecutionDate(LocalDate.of(2026, 7, 13));

        var plan = investmentService.createPlan(9L, request);

        assertThat(plan.getFrequency()).isEqualTo("DAILY");
        assertThat(plan.getExecutionDay()).isEqualTo(1);
        assertThat(plan.getNextExecutionDate()).isEqualTo(LocalDate.of(2026, 7, 13));
    }

    @Test
    void createPlan_shouldMoveMarketHolidayToNextTradingDay() {
        InvestmentAccountRequest accountRequest = new InvestmentAccountRequest();
        accountRequest.setAccountName("节假日定投账户");
        accountRequest.setAccountType("FUND_PLATFORM");
        var account = investmentService.createAccount(10L, accountRequest);

        InvestmentProductRequest product = new InvestmentProductRequest();
        product.setProductType("MUTUAL_FUND");
        product.setMarket("FUND_CN");
        product.setCode("010736");
        product.setName("测试节假日定投基金");
        product.setCurrency("CNY");

        InvestmentPlanRequest request = new InvestmentPlanRequest();
        request.setAccountId(account.getId());
        request.setProduct(product);
        request.setAmount(new BigDecimal("100"));
        request.setCurrency("CNY");
        request.setFrequency("MONTHLY");
        request.setExecutionDay(1);
        request.setNextExecutionDate(LocalDate.of(2026, 10, 1));

        var plan = investmentService.createPlan(10L, request);

        assertThat(plan.getNextExecutionDate()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    private static InvestmentTransactionRequest transaction(Long accountId,
                                                            InvestmentProductRequest product,
                                                            String type,
                                                            String quantity,
                                                            String price,
                                                            String fee) {
        InvestmentTransactionRequest request = new InvestmentTransactionRequest();
        request.setAccountId(accountId);
        request.setProduct(product);
        request.setEventType(type);
        request.setTradeDate(LocalDate.of(2026, 7, 10));
        request.setCurrency("CNY");
        request.setQuantity(new BigDecimal(quantity));
        request.setPrice(new BigDecimal(price));
        request.setFee(new BigDecimal(fee));
        return request;
    }
}
