package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.smartfinance.agent.investment.quant.workbench.WorkbenchAnalysisClient;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.*;

@Component
public class ExperimentCandidateService {
    private final WorkbenchAnalysisClient client;
    public ExperimentCandidateService(WorkbenchAnalysisClient client) { this.client=client; }
    public Map<String,Object> generate(Map<String,Object> config,String key,int assetCount,Map<String,Object> environment) {
        var response=client.candidates(Map.of("config",config,"parameterKey",key,"constraints",Map.of("effectiveAssetCount",assetCount)));
        require(Objects.equals(response.get("ruleVersion"),environment.get("candidateRuleVersion")),"EXPERIMENT_ENVIRONMENT_CHANGED");
        require(key.equals(response.get("parameterKey")) && response.get("values") instanceof List<?>,"INVALID_CANDIDATE_RESPONSE");
        var values=(List<?>)response.get("values");
        require(values.size()==5 && same(response.get("baseline"),config.get(key)),"INVALID_CANDIDATE_RESPONSE");
        try {
            var baseline=new BigDecimal(response.get("baseline").toString()); var delta=new BigDecimal(response.get("delta").toString());
            require(delta.signum()>0,"INVALID_CANDIDATE_RESPONSE");
            for(int i=0;i<5;i++) require(new BigDecimal(values.get(i).toString()).compareTo(baseline.add(delta.multiply(BigDecimal.valueOf(i-2))))==0,"INVALID_CANDIDATE_RESPONSE");
        } catch(NumberFormatException|NullPointerException error) { throw new ExperimentException("INVALID_CANDIDATE_RESPONSE"); }
        return response;
    }
}
