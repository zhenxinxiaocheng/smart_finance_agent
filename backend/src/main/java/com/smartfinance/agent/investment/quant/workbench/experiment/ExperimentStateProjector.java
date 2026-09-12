package com.smartfinance.agent.investment.quant.workbench.experiment;

import java.util.*;

public final class ExperimentStateProjector {
    public String project(List<String> statuses) {
        if(statuses.isEmpty()||statuses.stream().allMatch("QUEUED"::equals))return "QUEUED";
        if(statuses.stream().anyMatch(s->Set.of("QUEUED","RUNNING").contains(s)))return "RUNNING";
        if(statuses.stream().allMatch("SUCCEEDED"::equals))return "SUCCEEDED";
        if(statuses.stream().allMatch("CANCELLED"::equals))return "CANCELLED";
        if(statuses.contains("SUCCEEDED"))return "PARTIAL";
        return "FAILED";
    }
}
