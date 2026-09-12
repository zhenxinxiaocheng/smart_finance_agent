package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StabilityAnalyzerTest {
    List<StabilityAnalyzer.Point> points(double... returns){var out=new ArrayList<StabilityAnalyzer.Point>();for(int i=0;i<returns.length;i++)out.add(new StabilityAnalyzer.Point(i,48+i*6,returns[i],.12,"attempt-"+i));return out;}
    @Test void smoothNegativeIntervalIsStableAndClearlyNegative(){var result=new StabilityAnalyzer().analyze(points(-.12,-.11,-.10,-.11,-.12),5);assertThat(result).containsEntry("classification","STABLE").containsEntry("performanceProfile","NEGATIVE");assertThat(result.get("message")).isEqualTo("参数行为稳定，但该区间整体收益为负。");}
    @Test void isolatedBaselinePeakIsFragile(){var result=new StabilityAnalyzer().analyze(points(.01,.02,.15,.02,.01),5);assertThat(result).containsEntry("classification","FRAGILE").containsEntry("isolatedPeak",true);}
    @Test void continuousLocalReturnsAreStable(){assertThat(new StabilityAnalyzer().analyze(points(.11,.115,.12,.125,.13),5)).containsEntry("classification","STABLE").containsEntry("performanceProfile","POSITIVE");}
    @Test void signChangeAndFlatProfilesAreSeparate(){assertThat(new StabilityAnalyzer().analyze(points(-.01,-.005,0,.005,.01),5)).containsEntry("performanceProfile","MIXED");assertThat(new StabilityAnalyzer().analyze(points(0,0,0,0,0),5)).containsEntry("performanceProfile","FLAT");}
    @Test void noObservedRunsDoesNotClaimFlatPerformance(){assertThat(new StabilityAnalyzer().analyze(List.of(),5)).containsEntry("performanceProfile",null).containsEntry("classification","INSUFFICIENT");}
    @Test void unknownReasonIsRetainedAndConservative(){var result=new EvidenceQualityEvaluator().evaluate(true,true,true,5,5,List.of("NEW_ENGINE_REASON"));assertThat(result).containsEntry("level","MEDIUM");assertThat(((Map<?,?>)result.get("reasonClassifications")).containsKey("NEW_ENGINE_REASON")).isTrue();}
    @Test void drawdownOutcomeDoesNotDegradeEvidence(){assertThat(new EvidenceQualityEvaluator().evaluate(true,true,true,5,5,List.of("DRAWDOWN_LIMIT_EXCEEDED"))).containsEntry("level","HIGH");}
    @Test void statusComesOnlyFromExecution(){var p=new ExperimentStateProjector();assertThat(p.project(List.of("SUCCEEDED","SUCCEEDED"))).isEqualTo("SUCCEEDED");assertThat(p.project(List.of("FAILED","SUCCEEDED"))).isEqualTo("PARTIAL");assertThat(p.project(List.of("QUEUED","RUNNING"))).isEqualTo("RUNNING");assertThat(p.project(List.of("CANCELLED","CANCELLED"))).isEqualTo("CANCELLED");}
}
