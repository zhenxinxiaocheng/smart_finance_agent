package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StabilityV2Test {
    List<StabilityAnalyzer.Point> points(double... returns) {
        var points=new ArrayList<StabilityAnalyzer.Point>();
        for(int i=0;i<returns.length;i++)points.add(new StabilityAnalyzer.Point(i,48+6*i,returns[i],.12,"a"+i));
        return points;
    }
    Map<String,Object> analyze(List<StabilityAnalyzer.Point> points){return new StabilityAnalyzer().analyze(points,5);}
    @Test void stableCenterIgnoresUnstableOuterRuns() {
        assertThat(analyze(points(-.2,.09,.10,.11,.5))).containsEntry("classification","STABLE")
            .containsEntry("stableRange",List.of(54,66)).containsEntry("direction","FLAT").containsEntry("directionConsistency",1.0);
    }
    @Test void drawdownImprovementRemainsStable() {
        var p=points(.10,.10,.10,.10,.10);p.set(1,new StabilityAnalyzer.Point(1,54,.10,.01,"a1"));
        assertThat(analyze(p)).containsEntry("classification","STABLE").containsEntry("stableRange",List.of(48,72));
    }
    @Test void fewerThanThreeStablePointsIsFragile() {
        assertThat(analyze(points(.14,.14,.10,.10,.10))).containsEntry("classification","STABLE");
        assertThat(analyze(points(.14,.14,.10,.10,.14))).containsEntry("classification","FRAGILE");
    }
    @Test void isolatedPeakAndLargeImmediateJumpAreFragile() {
        assertThat(analyze(points(.01,.01,.10,.01,.01))).containsEntry("isolatedPeak",true).containsEntry("classification","FRAGILE");
        assertThat(analyze(points(.10,.10,.10,.16,.16))).containsEntry("classification","FRAGILE");
    }
    @Test void sufficientOpposingLocalDirectionsAreMixed() {
        assertThat(analyze(points(.10,.12,.10,.12,.10))).containsEntry("classification","STABLE");
        assertThat(analyze(points(.08,.12,.10,.12,.08))).containsEntry("classification","MIXED")
            .containsEntry("direction","MIXED").containsEntry("directionConsistency",.75);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3}) void missingCriticalPointIsInsufficient(int ordinal) {
        var p=points(.1,.1,.1,.1,.1);p.remove(ordinal);
        assertThat(analyze(p)).containsEntry("classification","INSUFFICIENT").containsEntry("directionConsistency",null);
    }
    @Test void onlyThreeValidPointsAreInsufficient() {
        assertThat(analyze(points(.1,.1,.1,.1,.1).subList(1,4))).containsEntry("classification","INSUFFICIENT");
    }
    @Test void missingOuterRunIsNeverBridged() {
        var p=points(.5,.1,.1,.1,.5);p.remove(0);
        assertThat(analyze(p)).containsEntry("stableRange",List.of(54,66)).containsEntry("directionConsistency",1.0);
    }
    @Test void negativeProfileStaysIndependent() {
        assertThat(analyze(points(-.12,-.11,-.1,-.11,-.12))).containsEntry("classification","STABLE")
            .containsEntry("performanceProfile","NEGATIVE").containsEntry("message","参数行为稳定，但该区间整体收益为负。");
    }
    @Test void localDirectionCountsOnlyChangesOutsideTolerance() {
        assertThat(analyze(points(.08,.12,.10,.10,.12))).containsEntry("direction","INCREASING").containsEntry("directionConsistency",1.0);
        assertThat(analyze(points(.12,.08,.10,.10,.08))).containsEntry("direction","DECREASING").containsEntry("directionConsistency",1.0);
    }
}
