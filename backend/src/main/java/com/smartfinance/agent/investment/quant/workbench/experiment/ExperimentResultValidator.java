package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.springframework.stereotype.Component;
import java.util.*;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.*;

@Component
public class ExperimentResultValidator {
    public static final String VERSION="experiment-controls-v1";
    public Map<String,Object> validate(ExperimentExecutionResolver.Resolved resolved,Map<String,Object> response) {
        var result=map(response.get("result"));var provenance=map(result.get("provenance"));var payload=resolved.payload();var experiment=resolved.experiment();
        List<String> mismatches=new ArrayList<>();
        check(mismatches,"config",payload.get("config"),provenance.get("config"));
        for(String key:List.of("engineVersion","codeHash","parameterCatalogVersion")) check(mismatches,key,experiment.environment().get(key),provenance.get(key));
        for(String key:List.of("strategyVersionId","startDate","endDate","modelRef","universeId"))check(mismatches,key,payload.get(key),provenance.get(key));
        check(mismatches,"universeVersion",payload.get("universeVersionId"),provenance.get("universeVersion"));
        check(mismatches,"factorSetVersionId",payload.get("factorVersionId"),provenance.get("factorSetVersionId"));
        check(mismatches,"benchmarkContract",experiment.sourceContext().get("benchmarkContract"),benchmark(result.get("benchmark")));
        check(mismatches,"assumptions",experiment.sourceContext().get("assumptions"),result.get("assumptions"));
        if(!(provenance.get("dataHash") instanceof String hash) || hash.isBlank())mismatches.add("dataHash");
        return Map.of("valid",mismatches.isEmpty(),"code",mismatches.isEmpty()?"":"CONTROL_VARIABLE_VIOLATION","mismatches",mismatches,"validatorVersion",VERSION,"attemptId",resolved.attempt().id());
    }
    public boolean sameData(Map<String,Object> baseline,Map<String,Object> candidate) {
        var left=map(map(baseline.get("result")).get("provenance"));var right=map(map(candidate.get("result")).get("provenance"));
        return left.get("dataHash")!=null && same(left.get("dataHash"),right.get("dataHash"));
    }
    private void check(List<String> errors,String key,Object expected,Object actual){if(!same(expected,actual))errors.add(key);}
}
