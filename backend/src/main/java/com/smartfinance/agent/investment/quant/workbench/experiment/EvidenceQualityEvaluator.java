package com.smartfinance.agent.investment.quant.workbench.experiment;

import java.util.*;

public final class EvidenceQualityEvaluator {
    public Map<String,Object> evaluate(boolean controls,boolean dataConsistent,boolean sourceComplete,int valid,int total,Collection<String> reasons) {
        var classifier=new EvidenceReasonClassifier();var classifications=new TreeMap<String,String>();
        reasons.forEach(code->classifications.put(code,classifier.classify(code).name()));
        boolean dataIssue=classifications.containsValue("DATA_QUALITY"), coverageIssue=classifications.containsValue("RUN_COVERAGE"), sourceIssue=classifications.containsValue("SOURCE_COMPLETENESS");
        String quality=!controls||!dataConsistent?"INVALID":!sourceComplete||valid<4?"INSUFFICIENT":dataIssue||sourceIssue?"LOW":valid<total||coverageIssue||classifications.containsValue("OTHER")?"MEDIUM":"HIGH";
        return Map.of("level",quality,"controlIntegrity",controls,"dataConsistency",dataConsistent&&!dataIssue,"sourceCompleteness",sourceComplete&&!sourceIssue,
                "runCoverage",Map.of("valid",valid,"total",total),"reasonClassifications",classifications);
    }
}
