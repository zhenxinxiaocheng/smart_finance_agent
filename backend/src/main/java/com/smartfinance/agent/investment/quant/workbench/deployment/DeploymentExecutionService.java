package com.smartfinance.agent.investment.quant.workbench.deployment;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.smartfinance.agent.investment.quant.workbench.client.WorkbenchAnalysisClient;
import com.smartfinance.agent.investment.quant.workbench.client.WorkbenchExecution;
import com.smartfinance.agent.investment.quant.workbench.market.WorkbenchDataPreparation;
import com.smartfinance.agent.investment.quant.workbench.support.WorkbenchJson;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeploymentExecutionService {
    private final DeploymentRepository deploymentRepository;
    private final WorkbenchDataPreparation preparation;
    private final WorkbenchExecution execution;
    private final WorkbenchJson json;
    private final TransactionTemplate tx;
    private final WorkbenchAnalysisClient client;

    public DeploymentExecutionService(
            DeploymentRepository deploymentRepository,
            WorkbenchDataPreparation preparation,
            WorkbenchExecution execution,
            WorkbenchJson json,
            PlatformTransactionManager manager,
            WorkbenchAnalysisClient client) {
        this.deploymentRepository = deploymentRepository;
        this.preparation = preparation;
        this.execution = execution;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        this.client = client;
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DeploymentExecutionService.class);

    public void paper() {
        try {
            var rows =
                    deploymentRepository.next(
                            System.currentTimeMillis(), System.currentTimeMillis());
            if (rows.isEmpty()) return;
            var deployment = rows.get(0);
            String identity = str(deployment.get("id")), claim = id();
            int revision = ((Number) deployment.get("revision")).intValue();
            if (deploymentRepository.claim(
                            claim,
                            System.currentTimeMillis() + client.leaseMillis(),
                            identity,
                            revision,
                            System.currentTimeMillis())
                    != 1) return;
            try {
                if (!preparation.prepareDeployment(deployment, claim)) return;
                Long user = ((Number) deployment.get("user_id")).longValue();
                var request = json.decode(deployment.get("request_json"));
                var previous = json.decode(deployment.get("result_json"));
                request.put("state", previous.getOrDefault("state", request.get("state")));
                String end = LocalDate.now().toString();
                request.put("endDate", end);
                String status = str(deployment.get("status"));
                request.put(
                        "action",
                        "PAUSED".equals(status)
                                ? "PAUSE"
                                : "STOPPING".equals(status) ? "LIQUIDATE" : "RUN");
                var response = execution.execute(request);
                tx.execute(
                        tx -> {
                            String next = status;
                            if ("STOPPING".equals(status)
                                    && Boolean.TRUE.equals(
                                            map(response.get("state")).get("liquidated")))
                                next = "STOPPED";
                            int saved =
                                    deploymentRepository.complete(
                                            json.encode(response),
                                            next,
                                            System.currentTimeMillis() + 60000,
                                            now(),
                                            identity,
                                            revision,
                                            claim);
                            if (saved == 1) {
                                var result = map(response.get("result"));
                                for (String key : List.of("orders", "fills", "cashLedger")) {
                                    Object list = result.get(key);
                                    if (list instanceof List<?> events)
                                        for (Object event : events)
                                            append(user, identity, key, map(event));
                                }
                            }
                            return null;
                        });
            } catch (Exception e) {
                var error = execution.error(e);
                deploymentRepository.fail(
                        error.get("code"),
                        error.get("message"),
                        System.currentTimeMillis() + 60000,
                        now(),
                        identity,
                        revision,
                        claim);
            }
        } catch (Exception e) {
            log.error("量化模拟组合调度失败", e);
        }
    }

    void append(Long user, String deployment, String kind, Map<String, Object> payload) {
        String value = json.encode(payload);
        String key =
                kind
                        + ":"
                        + UUID.nameUUIDFromBytes(
                                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (deploymentRepository.eventCount(deployment, key) == 0)
            deploymentRepository.insertEvent(id(), user, deployment, key, kind, value, now());
    }
}
