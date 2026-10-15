package com.smartfinance.agent.investment.quant.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.quant.QuantBenchmarkProfileService;
import com.smartfinance.agent.investment.quant.workbench.api.WorkbenchController;
import com.smartfinance.agent.investment.quant.workbench.client.WorkbenchAnalysisClient;
import com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentExecutionResolver;
import com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentResultValidator;
import com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentSummaryService;
import com.smartfinance.agent.investment.quant.workbench.task.TaskService;
import com.smartfinance.agent.investment.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkbenchWiringHttpTest {
    private AnnotationConfigApplicationContext context;
    private MockMvc http;
    private JdbcTemplate db;
    private ObjectMapper json;
    private WorkbenchAnalysisClient client;
    private MarketDataDemandService demand;

    @BeforeEach
    void wireRealWorkbenchBeans() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(source);
        db.update("INSERT INTO investment_product(id,name,code,product_type,market,fund_category) "
                + "VALUES(1,'fixture','000001','MUTUAL_FUND','FUND_CN','INDEX_FUND')");
        var manager = new DataSourceTransactionManager(source);
        json = new ObjectMapper();
        var provider = mock(AnalysisServiceClient.class);
        client = mock(WorkbenchAnalysisClient.class);
        when(client.dataRequirements(anyMap())).thenAnswer(call -> Map.of("config", call.getArgument(0),
                "datasets", List.of("PRICE"), "warmupTradingDays", 60));
        demand = mock(MarketDataDemandService.class);
        when(demand.boundedStart(anyLong(), any())).thenAnswer(call -> call.getArgument(1));
        var horizons = new InvestmentHorizonProperties();
        horizons.setCalendarDaysPerYear(365);
        horizons.setTradingDaysPerYear(240);
        horizons.setCalendarBufferDays(30);

        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().setConversionService(new org.springframework.boot.convert.ApplicationConversionService());
        // The real constructor graph is scanned; only external integrations and the separately
        // tested experiment boundary are replaced. No mock ordinary service/repository is used.
        registerIntegration(ObjectMapper.class, () -> json);
        registerIntegration(JdbcTemplate.class, () -> db);
        registerIntegration(PlatformTransactionManager.class, () -> manager);
        registerIntegration(AnalysisServiceClient.class, () -> provider);
        registerIntegration(WorkbenchAnalysisClient.class, () -> client);
        registerIntegration(MarketDataDemandService.class, () -> demand);
        registerIntegration(MarketDataRequirementService.class, () -> new MarketDataRequirementService(db));
        registerIntegration(InvestmentHorizonProperties.class, () -> horizons);
        registerIntegration(MarketDataService.class, () -> new MarketDataService(db, provider, QuoteMapperTestSupport.create(source)));
        registerIntegration(InvestmentDataQualityService.class, () -> new InvestmentDataQualityService(
                null, null, null, new InvestmentRuntimeProperties(), json));
        registerIntegration(InvestmentHistoryPreparationService.class, () -> new InvestmentHistoryPreparationService(
                null, null, null, null, null, null, manager));
        registerIntegration(QuantBenchmarkProfileService.class, () -> mock(QuantBenchmarkProfileService.class));
        registerIntegration(ExperimentExecutionResolver.class, () -> mock(ExperimentExecutionResolver.class));
        registerIntegration(ExperimentResultValidator.class, ExperimentResultValidator::new);
        registerIntegration(ExperimentSummaryService.class, () -> mock(ExperimentSummaryService.class));
        var scanner = new ClassPathBeanDefinitionScanner(context);
        scanner.addExcludeFilter(new AssignableTypeFilter(WorkbenchAnalysisClient.class));
        scanner.addExcludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*\\.workbench\\.experiment\\..*")));
        scanner.scan("com.smartfinance.agent.investment.quant.workbench");
        context.refresh();
        http = MockMvcBuilders.standaloneSetup(context.getBean(WorkbenchController.class)).build();
    }

    private <T> void registerIntegration(Class<T> type, java.util.function.Supplier<T> factory) {
        context.getBeanFactory().registerSingleton(java.beans.Introspector.decapitalize(type.getSimpleName()), factory.get());
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    private String create(String kind, Map<String, Object> body) throws Exception {
        var result = http.perform(post("/api/quant/v2/" + kind).requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).at("/data/id").asText();
    }

    private String strategy() throws Exception {
        String universe = create("universes", Map.of("name", "pool", "assetClass", "FUND", "productIds", List.of(1)));
        return create("strategies", Map.of("name", "strategy", "universeId", universe, "config", Map.of("strategyType", "TREND")));
    }

    @Test
    void originalRoutesCreateFreezeCancelRetryAndProtectOwnership() throws Exception {
        String strategy = strategy();
        String task = create("backtests", Map.of("strategyId", strategy, "startDate", "2023-01-01", "endDate", "2023-03-02"));
        http.perform(get("/api/quant/v2/backtests/" + task).requestAttr("userId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stage").value("DATA_PREPARING"))
                .andExpect(jsonPath("$.data.researchContext.lineage.strategy.state").value("AVAILABLE"));
        http.perform(get("/api/quant/v2/backtests/" + task).requestAttr("userId", 2L)).andExpect(status().isNotFound());
        http.perform(get("/api/quant/v2/strategies/" + strategy + "/versions").requestAttr("userId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        http.perform(post("/api/quant/v2/backtests/" + task + "/cancel").requestAttr("userId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        http.perform(post("/api/quant/v2/backtests/" + task + "/retry").requestAttr("userId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stage").value("DATA_PREPARING"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_task", Integer.class)).isEqualTo(2);
    }

    @Test
    void failedRequirementRegistrationRollsBackTaskAndFrozenVersionsTogether() throws Exception {
        String strategy = strategy();
        when(demand.boundedStart(anyLong(), any())).thenThrow(new IllegalStateException("preparation unavailable"));
        assertThatThrownBy(() -> context.getBean(TaskService.class).createTask(1L, "backtests", Map.of(
                "strategyId", strategy, "startDate", "2023-01-01", "endDate", "2023-03-02")))
                .hasMessageContaining("preparation unavailable");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_task", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_version", Integer.class)).isZero();
        assertThat(context.getBean(MarketDataRequirementService.class).active()).isEmpty();
    }
}
