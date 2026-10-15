package com.smartfinance.agent.investment.quant.workbench.task;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.smartfinance.agent.investment.quant.workbench.client.WorkbenchAnalysisClient;
import com.smartfinance.agent.investment.quant.workbench.client.WorkbenchExecution;
import com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentExecutionResolver;
import com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentResultValidator;
import com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentSummaryService;
import com.smartfinance.agent.investment.quant.workbench.market.WorkbenchDataPreparation;
import com.smartfinance.agent.investment.quant.workbench.support.WorkbenchJson;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;

@Service
public class TaskExecutionService {
    private final TaskRepository taskRepository;
    private final WorkbenchDataPreparation preparation;
    private final WorkbenchExecution execution;
    private final WorkbenchJson json;
    private final ExperimentExecutionResolver resolver;
    private final ExperimentResultValidator validator;
    private final ExperimentSummaryService summaries;
    private final WorkbenchAnalysisClient client;

    public TaskExecutionService(
            TaskRepository taskRepository,
            WorkbenchDataPreparation preparation,
            WorkbenchExecution execution,
            WorkbenchJson json,
            ExperimentExecutionResolver resolver,
            ExperimentResultValidator validator,
            ExperimentSummaryService summaries,
            WorkbenchAnalysisClient client) {
        this.taskRepository = taskRepository;
        this.preparation = preparation;
        this.execution = execution;
        this.json = json;
        this.resolver = resolver;
        this.validator = validator;
        this.summaries = summaries;
        this.client = client;
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TaskExecutionService.class);

    public void tasks() {
        try {
            var rows = taskRepository.next(System.currentTimeMillis(), System.currentTimeMillis());
            if (rows.isEmpty()) return;
            var task = rows.get(0);
            String identity = str(task.get("id")), claim = id();
            int claimed =
                    taskRepository.claim(
                            claim,
                            System.currentTimeMillis() + client.leaseMillis(),
                            now(),
                            identity,
                            System.currentTimeMillis());
            if (claimed != 1) return;
            try {
                summaries.taskChanged(((Number) task.get("user_id")).longValue(), identity);
            } catch (Exception e) {
                log.error("参数敏感性状态投影失败", e);
            }
            try {
                if (!preparation.prepare(task, claim)) return;
                Long user = ((Number) task.get("user_id")).longValue();
                var input = resolver.resolve(user, identity, json.decode(task.get("request_json")));
                var result = execution.execute(input.payload());
                if (input.experiment() != null) {
                    result = new LinkedHashMap<>(result);
                    result.put("experimentValidation", validator.validate(input, result));
                }
                taskRepository.complete(json.encode(result), now(), identity, claim);
            } catch (Exception e) {
                var error = execution.error(e);
                taskRepository.fail(
                        error.get("code"), error.get("message"), now(), identity, claim);
            }
            try {
                summaries.taskChanged(((Number) task.get("user_id")).longValue(), identity);
            } catch (Exception e) {
                log.error("参数敏感性汇总失败，任务原始结果已保留", e);
            }
        } catch (Exception e) {
            log.error("量化工作台任务调度失败", e);
        }
    }
}
