package com.smartfinance.agent.investment.quant.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.quant.workbench.client.*;
import com.smartfinance.agent.investment.quant.workbench.configuration.*;
import com.smartfinance.agent.investment.quant.workbench.deployment.*;
import com.smartfinance.agent.investment.quant.workbench.experiment.*;
import com.smartfinance.agent.investment.quant.workbench.market.*;
import com.smartfinance.agent.investment.quant.workbench.query.*;
import com.smartfinance.agent.investment.quant.workbench.scheduling.WorkbenchWorker;
import com.smartfinance.agent.investment.quant.workbench.support.WorkbenchJson;
import com.smartfinance.agent.investment.quant.workbench.task.*;
import com.smartfinance.agent.investment.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit test wiring; assertions call the same business services as production. */
public final class WorkbenchTestContext {
    public final WorkbenchJson json;
    public final WorkbenchMarketService market;
    public final WorkbenchQueryService queries;
    public final ConfigurationService configuration;
    public final TaskRepository taskRepository;
    public final DeploymentRepository deploymentRepository;
    public TaskService tasks;
    public DeploymentService deployments;
    private final ConfigurationRepository configurations;
    private final WorkbenchQueryRepository records;
    private final WorkbenchTrackingIndex tracking;
    private final PlatformTransactionManager manager;
    private WorkbenchDataPreparation preparation;
    public final WorkbenchAnalysisClient client;
    private final ExperimentExecutionResolver resolver;
    private final ExperimentResultValidator validator;
    private final ExperimentSummaryService summaries;

    public WorkbenchTestContext(JdbcTemplate db, ObjectMapper mapper, PlatformTransactionManager manager,
            WorkbenchTrackingIndex tracking, AnalysisServiceClient provider,
            InvestmentDataQualityService quality, MarketDataService marketData) {
        this.manager = manager;
        this.tracking = tracking;
        json = new WorkbenchJson(mapper);
        market = new WorkbenchMarketService(new WorkbenchMarketRepository(db), provider, quality, marketData);
        records = new WorkbenchQueryRepository(db);
        queries = new WorkbenchQueryService(records, json, new WorkbenchResearchContext(records, json), market);
        configurations = new ConfigurationRepository(db);
        configuration = new ConfigurationService(configurations, queries, market, json, manager);
        taskRepository = new TaskRepository(db);
        deploymentRepository = new DeploymentRepository(db);
        client = org.mockito.Mockito.mock(WorkbenchAnalysisClient.class);
        org.mockito.Mockito.when(client.leaseMillis()).thenReturn(60000L);
        org.mockito.Mockito.when(client.dataRequirements(org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(call -> java.util.Map.of("config", call.getArgument(0), "warmupTradingDays", 60,
                        "datasets", java.util.List.of("PRICE")));
        org.mockito.Mockito.when(client.execute(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(java.util.Map.of("status", "SUCCEEDED"));
        var requirements = new MarketDataRequirementService(db);
        var demand = org.mockito.Mockito.mock(MarketDataDemandService.class);
        org.mockito.Mockito.when(demand.boundedStart(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(1));
        org.mockito.Mockito.when(demand.prepared(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        var properties = new InvestmentHorizonProperties();
        properties.setCalendarDaysPerYear(365);
        properties.setTradingDaysPerYear(240);
        properties.setCalendarBufferDays(30);
        configurePreparation(preparation(requirements, demand, client, properties,
                new InvestmentHistoryPreparationService(null, null, null, null, null, null, manager)));
        var experiments = new ExperimentRepository(db, mapper);
        resolver = new ExperimentExecutionResolver(experiments, new ResearchSnapshotStore(db, mapper, 1000000, 1000000), db);
        validator = new ExperimentResultValidator();
        summaries = new ExperimentSummaryService(db, experiments, validator, manager);
    }

    public WorkbenchDataPreparation preparation(MarketDataRequirementService requirements, MarketDataDemandService demand,
            WorkbenchAnalysisClient client, InvestmentHorizonProperties properties, InvestmentHistoryPreparationService history) {
        return new WorkbenchDataPreparation(requirements, demand, client, properties, history, market, tracking,
                taskRepository, deploymentRepository, json, manager);
    }

    public void configurePreparation(WorkbenchDataPreparation preparation) {
        this.preparation = preparation;
        tasks = new TaskService(taskRepository, configuration, queries, market, preparation, json, manager);
        deployments = new DeploymentService(deploymentRepository, configuration, queries, market, tasks, json, manager);
    }

    public WorkbenchWorker worker(WorkbenchAnalysisClient client) {
        return worker(client, resolver, validator, summaries);
    }

    public WorkbenchWorker worker(WorkbenchAnalysisClient client, ExperimentExecutionResolver resolver,
            ExperimentResultValidator validator, ExperimentSummaryService summaries) {
        var execution = new WorkbenchExecution(client, json);
        return new WorkbenchWorker(new TaskExecutionService(taskRepository, preparation, execution, json,
                resolver, validator, summaries, client),
                new DeploymentExecutionService(deploymentRepository, preparation, execution, json, manager, client));
    }
}
