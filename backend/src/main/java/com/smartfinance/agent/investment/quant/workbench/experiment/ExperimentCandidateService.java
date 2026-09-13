package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.smartfinance.agent.investment.quant.workbench.WorkbenchAnalysisClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.*;

@Component
public class ExperimentCandidateService {
    private static final Pattern MACHINE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,79}$");
    private final WorkbenchAnalysisClient client;
    public ExperimentCandidateService(WorkbenchAnalysisClient client) { this.client=client; }
    public Map<String,Object> generate(Map<String,Object> config,String key,int assetCount,Map<String,Object> environment) {
        Map<String,Object> response;
        try {
            response=client.candidates(Map.of("config",config,"parameterKey",key,"constraints",Map.of("effectiveAssetCount",assetCount)));
        } catch (HttpClientErrorException.UnprocessableEntity error) {
            String reasonCode=candidateReasonCode(error);
            if(reasonCode==null)throw error;
            throw new ExperimentException("INVALID_EXPERIMENT_REQUEST",reasonCode,candidateSafeMessage(reasonCode));
        }
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
    private static String candidateReasonCode(HttpClientErrorException.UnprocessableEntity error) {
        try {
            Object code=map(decode(error.getResponseBodyAsString()).get("detail")).get("code");
            return code instanceof String text && MACHINE_CODE.matcher(text).matches()?text:null;
        } catch(RuntimeException malformedResponse) {
            return null;
        }
    }
    private static String candidateSafeMessage(String reasonCode) {
        return switch(reasonCode) {
            case "PARAMETER_NOT_APPLICABLE" -> "当前策略不支持对该参数进行敏感性检查";
            case "SYMMETRIC_RANGE_UNAVAILABLE" -> "当前参数附近无法形成完整的五点对称测试区间";
            case "INVALID_BASELINE" -> "当前参数值无法用于敏感性检查";
            case "UNKNOWN_PARAMETER" -> "未找到可用于敏感性检查的参数";
            case "INVALID_CONSTRAINTS" -> "当前参数约束无法用于敏感性检查";
            default -> "当前参数无法完成敏感性检查";
        };
    }
}
