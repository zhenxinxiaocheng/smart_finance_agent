package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.common.GlobalExceptionHandler;
import com.smartfinance.agent.investment.quant.workbench.WorkbenchAnalysisClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExperimentProductHttpIntegrationTest {
    private HttpServer server;
    private JdbcTemplate db;
    private MockMvc mvc;
    private String sourceId;
    private volatile String candidateCode;
    private final List<Map<String, String>> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Object> config = Map.of("slowWindow", 60, "feeRate", .001);
    private final Map<String, Object> benchmark = Map.of("status", "READY", "type", "UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE");
    private final Map<String, Object> runtime = Map.of("engineVersion", "current", "codeHash", "current-code",
            "parameterCatalogVersion", "catalog", "candidateRuleVersion", "candidate-v1");

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(
                new ClassPathResource("schema-h2.sql"))
                .execute(source);
        var json = new ObjectMapper();
        var repository = new ExperimentRepository(db, json);
        var manager = new DataSourceTransactionManager(source);
        var snapshots = new ResearchSnapshotStore(db, json, 1_000_000, 1_000_000);
        var tasks = new ExperimentTaskStore(db, json);
        var attempts = new ExperimentAttemptService(db, repository, tasks, manager);
        seedSource(tasks);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quant/v2/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(Map.of("path", path, "method", exchange.getRequestMethod(),
                    "token", String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Token")),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int httpStatus = 200;
            Object response;
            switch (path) {
                case "/quant/v2/runtime-info" -> response = runtime;
                case "/quant/v2/validate-config" -> response = Map.of("compatible", true,
                        "effectiveConfig", config, "runtime", runtime,
                        "assumptions", List.of("fixed assumptions"), "benchmarkContract", benchmark);
                case "/quant/v2/parameter-sensitivity/candidates" -> {
                    httpStatus = 422;
                    response = Map.of("detail", Map.of("code", candidateCode, "message", "python /internal/private/path"));
                }
                default -> { httpStatus = 404; response = Map.of(); }
            }
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(httpStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = new WorkbenchAnalysisClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-token", Duration.ofSeconds(5));
        var commands = new ExperimentService(db, repository, snapshots,
                new ExperimentCandidateService(client), client, attempts, manager);
        mvc = MockMvcBuilders.standaloneSetup(new ExperimentController(new ExperimentProductService(commands, repository)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void close() { if (server != null) server.stop(0); }

    @ParameterizedTest
    @CsvSource({
            "PARAMETER_NOT_APPLICABLE,400,INVALID_EXPERIMENT_REQUEST,当前策略不支持对该参数进行敏感性检查",
            "bad-code,503,ANALYSIS_SERVICE_UNAVAILABLE,分析服务暂时不可用，请稍后重试"
    })
    void candidateHttpFailureReachesProductResponseWithoutLeakingPythonMessage(
            String upstreamCode, int expectedStatus, String errorCode, String safeMessage) throws Exception {
        candidateCode = upstreamCode;
        var result = mvc.perform(post("/api/quant/v2/experiments")
                        .requestAttr("userId", 7L).header("Idempotency-Key", "http-contract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(encode(Map.of("sourceBacktestId", sourceId, "parameterKey", "slowWindow", "name", "参数敏感性检查"))))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedStatus))
                .andExpect(jsonPath("$.message").value(safeMessage))
                .andExpect(jsonPath("$.data.errorCode").value(errorCode));
        if (expectedStatus == 400) result.andExpect(jsonPath("$.data.reasonCode").value(upstreamCode));
        else result.andExpect(jsonPath("$.data.reasonCode").doesNotExist());
        assertThat(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("python", "/internal", "/private/path", "bad-code");
        assertThat(calls).extracting(call -> call.get("path")).containsExactly(
                "/quant/v2/runtime-info", "/quant/v2/validate-config", "/quant/v2/parameter-sensitivity/candidates");
        assertThat(calls).allSatisfy(call -> assertThat(call.get("token")).isEqualTo("test-token"));
        assertThat(calls.get(2).get("method")).isEqualTo("POST");
        assertThat(calls.get(2).get("body")).contains("slowWindow", "60");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_experiment", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_experiment_run_attempt", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_task", Integer.class)).isEqualTo(1);
    }

    private void seedSource(ExperimentTaskStore tasks) {
        db.update("INSERT INTO quant_v2_object VALUES('strategy',7,'strategies','策略','ACTIVE',1,'{}','now','now')");
        db.update("INSERT INTO quant_v2_object VALUES('universe',7,'universes','研究池','ACTIVE',1,'{}','now','now')");
        db.update("INSERT INTO quant_v2_version VALUES('strategy-v1',7,'strategy',1,?,'now')", encode(Map.of("name", "冻结策略")));
        db.update("INSERT INTO quant_v2_version VALUES('universe-v1',7,'universe',1,'{}','now')");
        var request = Map.<String, Object>of("kind", "BACKTEST", "strategyVersionId", "strategy-v1",
                "universeId", "universe", "universeVersionId", "universe-v1", "config", config,
                "startDate", "2024-01-01", "endDate", "2024-12-31",
                "assets", List.of(Map.of("id", "asset", "code", "000001", "assetClass", "STOCK",
                        "bars", List.of(Map.of("date", "2024-01-01", "close", 10)))));
        sourceId = tasks.enqueueBacktest(7L, "正式回测", "strategy", "strategy-v1", "universe", request);
        var provenance = Map.of("engineVersion", "source-engine", "codeHash", "source-code",
                "dataHash", "source-data", "config", config, "strategyVersionId", "strategy-v1",
                "universeId", "universe", "universeVersion", "universe-v1",
                "startDate", "2024-01-01", "endDate", "2024-12-31");
        db.update("UPDATE quant_v2_task SET status='SUCCEEDED',stage='COMPLETED',result_json=? WHERE id=?",
                encode(Map.of("status", "SUCCEEDED", "result", Map.of("provenance", provenance,
                        "assumptions", List.of("fixed assumptions"), "benchmark", benchmark))), sourceId);
    }
}
