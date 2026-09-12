package com.smartfinance.agent.investment.quant.workbench.experiment;

import java.util.*;

public final class EvidenceQualityEvaluator {
    public static final String VERSION="experiment-evidence-v2";
    public Map<String,Object> evaluate(boolean controls,boolean dataConsistent,boolean sourceComplete,int valid,int total,Collection<String> reasons) {
        return evaluate(controls?"PASS":"FAIL",dataConsistent?"PASS":"FAIL",sourceComplete?"COMPLETE":"INSUFFICIENT",
                valid>=4?List.of(0,1,2,3):List.of(),valid,total,List.of(),reasons);
    }
    public Map<String,Object> evaluate(String controls,String data,String source,List<Integer> ordinals,int valid,int total,
                                       List<Map<String,Object>> exclusions,Collection<String> reasons) {
        var classifier=new EvidenceReasonClassifier();var classifications=new TreeMap<String,String>();
        reasons.forEach(code->classifications.put(code,classifier.classify(code).name()));
        if(classifications.containsValue("CONTROL_INTEGRITY"))controls="FAIL";
        if(classifications.containsValue("DATA_INCONSISTENCY"))data="FAIL";
        else if(!"FAIL".equals(data)&&classifications.containsValue("DATA_QUALITY"))data="WARN";
        if("COMPLETE".equals(source)&&classifications.containsValue("SOURCE_COMPLETENESS"))source="PARTIAL";
        String coverage=valid<4||!ordinals.containsAll(List.of(1,2,3))?"INSUFFICIENT":valid<total||classifications.containsValue("RUN_COVERAGE")?"PARTIAL":"COMPLETE";
        String quality="FAIL".equals(controls)||"FAIL".equals(data)?"INVALID":
                "INSUFFICIENT".equals(source)||"INSUFFICIENT".equals(coverage)?"INSUFFICIENT":
                "PARTIAL".equals(controls)||"WARN".equals(data)||"PARTIAL".equals(source)?"LOW":
                "PARTIAL".equals(coverage)||classifications.containsValue("OTHER")?"MEDIUM":"HIGH";
        var out=new LinkedHashMap<String,Object>();out.put("level",quality);out.put("controlIntegrity",controls);out.put("dataConsistency",data);
        out.put("sourceCompleteness",source);out.put("runCoverage",coverage);out.put("validRuns",valid);out.put("totalRuns",total);
        out.put("excludedRunCount",total-valid);out.put("excludedRuns",exclusions);out.put("reasonClassifications",classifications);return out;
    }
}
