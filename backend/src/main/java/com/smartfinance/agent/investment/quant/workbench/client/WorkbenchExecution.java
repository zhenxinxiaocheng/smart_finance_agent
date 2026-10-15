package com.smartfinance.agent.investment.quant.workbench.client;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant;
import com.smartfinance.agent.investment.quant.workbench.experiment.ResearchSnapshotStore;
import com.smartfinance.agent.investment.quant.workbench.support.WorkbenchJson;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Service
public class WorkbenchExecution {
    private final WorkbenchAnalysisClient client;
    private final WorkbenchJson json;

    public WorkbenchExecution(WorkbenchAnalysisClient client, WorkbenchJson json) {
        this.client = client;
        this.json = json;
    }

    public Map<String, Object> execute(Map<String, Object> request) {
        var response = client.execute(request);
        if (!"SUCCEEDED".equals(response.get("status")))
            throw new WorkbenchExecutionException(
                    str(response.getOrDefault("errorCode", "ENGINE_FAILED")),
                    str(response.getOrDefault("errorMessage", "分析任务执行失败")));
        return response;
    }

    public Map<String, String> error(Exception e) {
        if (e instanceof ExperimentInvariant.ExperimentException x)
            return Map.of("code", x.code(), "message", x.getMessage());
        if (e instanceof ResearchSnapshotStore.SnapshotException x)
            return Map.of("code", x.code(), "message", x.getMessage());
        if (e instanceof WorkbenchExecutionException f)
            return Map.of("code", f.code(), "message", f.getMessage());
        if (e instanceof RestClientResponseException http) {
            try {
                var body = json.decode(http.getResponseBodyAsString());
                var detail = map(body.get("detail"));
                return Map.of(
                        "code",
                        str(
                                detail.getOrDefault(
                                        "code", "ANALYSIS_HTTP_" + http.getStatusCode().value())),
                        "message",
                        str(
                                detail.getOrDefault(
                                        "message", body.getOrDefault("detail", "分析服务请求失败"))));
            } catch (Exception ignored) {
                return Map.of(
                        "code",
                        "ANALYSIS_HTTP_" + http.getStatusCode().value(),
                        "message",
                        "分析服务返回错误，请检查服务日志");
            }
        }
        return Map.of(
                "code",
                "ANALYSIS_EXECUTION_FAILED",
                "message",
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
}
