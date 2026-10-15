package com.smartfinance.agent.investment.quant.workbench.scheduling;

import com.smartfinance.agent.investment.quant.workbench.deployment.DeploymentExecutionService;
import com.smartfinance.agent.investment.quant.workbench.task.TaskExecutionService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkbenchWorker {
    private final TaskExecutionService tasks;
    private final DeploymentExecutionService deployments;

    public WorkbenchWorker(TaskExecutionService tasks, DeploymentExecutionService deployments) {
        this.tasks = tasks;
        this.deployments = deployments;
    }

    @Scheduled(
            scheduler = "quantWorkbenchScheduler",
            fixedDelayString = "${quant.workbench.poll-ms:3000}")
    public void tasks() {
        tasks.tasks();
    }

    @Scheduled(
            scheduler = "quantWorkbenchScheduler",
            fixedDelayString = "${quant.workbench.paper-poll-ms:30000}")
    public void paper() {
        deployments.paper();
    }
}
