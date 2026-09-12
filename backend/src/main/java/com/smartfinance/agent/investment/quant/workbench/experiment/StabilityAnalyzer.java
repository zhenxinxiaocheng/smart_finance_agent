package com.smartfinance.agent.investment.quant.workbench.experiment;

import java.util.*;

public final class StabilityAnalyzer {
    public static final String V1="parameter-stability-v1";
    public static final String VERSION="parameter-stability-v2";
    public record Point(int ordinal,Object value,double netReturn,double maxDrawdown,String attemptId) {}
    public Map<String,Object> analyze(List<Point> input,int total) {
        return analyze(VERSION,input,total);
    }
    public Map<String,Object> analyze(String version,List<Point> input,int total) {
        ExperimentInvariant.require(V1.equals(version)||VERSION.equals(version),"STABILITY_ALGORITHM_UNAVAILABLE");
        return V1.equals(version)?analyzeV1(input,total):analyzeV2(input,total);
    }
    private Map<String,Object> analyzeV2(List<Point> input,int total) {
        var points=input.stream().sorted(Comparator.comparingInt(Point::ordinal)).toList();
        var out=new LinkedHashMap<String,Object>();
        out.put("algorithmVersion",VERSION);out.put("validRunCount",points.size());out.put("excludedRunCount",total-points.size());
        out.put("inputAttemptIds",points.stream().map(Point::attemptId).toList());
        boolean positive=points.stream().anyMatch(p->p.netReturn()>1e-12),negative=points.stream().anyMatch(p->p.netReturn() < -1e-12);
        out.put("performanceProfile",points.isEmpty()?null:positive&&negative?"MIXED":positive?"POSITIVE":negative?"NEGATIVE":"FLAT");
        var byOrdinal=new TreeMap<Integer,Point>();points.forEach(p->byOrdinal.put(p.ordinal(),p));
        if(points.size()<4 || !byOrdinal.keySet().containsAll(List.of(1,2,3))) {
            out.put("classification","INSUFFICIENT");out.put("stableRange",List.of());out.put("isolatedPeak",false);
            out.put("localSensitivity",null);out.put("direction",null);out.put("directionConsistency",null);
            out.put("maxAdjacentReturnChange",null);out.put("maxAdjacentDrawdownChange",null);
            out.put("reasonCodes",List.of("MINIMUM_LOCAL_RUN_COVERAGE_NOT_MET"));return out;
        }
        var base=byOrdinal.get(2);double rt=Math.max(.02,Math.abs(base.netReturn())*.25),dt=Math.max(.02,base.maxDrawdown()*.20);
        int low=2,high=2;
        while(byOrdinal.containsKey(low-1)&&withinV2(byOrdinal.get(low-1),base,rt,dt))low--;
        while(byOrdinal.containsKey(high+1)&&withinV2(byOrdinal.get(high+1),base,rt,dt))high++;
        int up=0,down=0,flat=0;double maxReturn=0,maxDrawdown=0;
        for(int i=low;i<high;i++) {
            var left=byOrdinal.get(i);var right=byOrdinal.get(i+1);double delta=right.netReturn()-left.netReturn();
            maxReturn=Math.max(maxReturn,Math.abs(delta));maxDrawdown=Math.max(maxDrawdown,Math.abs(right.maxDrawdown()-left.maxDrawdown()));
            if(Math.abs(delta)<=rt+1e-12)flat++;else if(delta>rt)up++;else down++;
        }
        int transitions=up+down+flat;
        Double consistency=transitions==0?null:(double)(Math.max(up,down)+flat)/transitions;
        String direction=transitions==0?null:up==0&&down==0?"FLAT":up>down?"INCREASING":down>up?"DECREASING":"MIXED";
        double localReturn=Math.max(Math.abs(base.netReturn()-byOrdinal.get(1).netReturn()),Math.abs(base.netReturn()-byOrdinal.get(3).netReturn()));
        boolean peak=base.netReturn()-byOrdinal.get(1).netReturn()>rt+1e-12 && base.netReturn()-byOrdinal.get(3).netReturn()>rt+1e-12;
        boolean jump=localReturn>2*rt+1e-12;
        var reasons=new ArrayList<String>();if(peak)reasons.add("ISOLATED_BASELINE_PEAK");if(jump)reasons.add("LOCAL_RETURN_JUMP");
        if(high-low+1<3)reasons.add("STABLE_RANGE_TOO_NARROW");
        String classification=!reasons.isEmpty()?"FRAGILE":consistency!=null&&consistency>=.80?"STABLE":"MIXED";
        out.put("classification",classification);out.put("stableRange",List.of(byOrdinal.get(low).value(),byOrdinal.get(high).value()));out.put("isolatedPeak",peak);
        out.put("returnTolerance",rt);out.put("drawdownTolerance",dt);out.put("maxAdjacentReturnChange",maxReturn);out.put("maxAdjacentDrawdownChange",maxDrawdown);
        out.put("localSensitivity",Map.of("return",localReturn,"drawdown",Math.max(Math.abs(base.maxDrawdown()-byOrdinal.get(1).maxDrawdown()),Math.abs(base.maxDrawdown()-byOrdinal.get(3).maxDrawdown()))));
        out.put("direction",direction);out.put("directionConsistency",consistency);out.put("reasonCodes",reasons);
        if("STABLE".equals(classification)&&"NEGATIVE".equals(out.get("performanceProfile")))out.put("message","参数行为稳定，但该区间整体收益为负。");
        return out;
    }
    private boolean withinV2(Point p,Point base,double rt,double dt) {
        return Math.abs(p.netReturn()-base.netReturn())<=rt+1e-12 && p.maxDrawdown()<=base.maxDrawdown()+dt+1e-12;
    }
    private Map<String,Object> analyzeV1(List<Point> input,int total) {
        var points=input.stream().sorted(Comparator.comparingInt(Point::ordinal)).toList();
        var out=new LinkedHashMap<String,Object>();out.put("algorithmVersion",V1);out.put("validRunCount",points.size());out.put("excludedRunCount",total-points.size());
        out.put("inputAttemptIds",points.stream().map(Point::attemptId).toList());
        boolean positive=points.stream().anyMatch(p->p.netReturn()>1e-12),negative=points.stream().anyMatch(p->p.netReturn() < -1e-12);
        out.put("performanceProfile",points.isEmpty()?null:positive&&negative?"MIXED":positive?"POSITIVE":negative?"NEGATIVE":"FLAT");
        var baseline=points.stream().filter(p->p.ordinal()==2).findFirst();
        var ordinals=points.stream().map(Point::ordinal).toList();
        if(points.size()<4 || !ordinals.containsAll(List.of(1,2,3))) {
            out.put("classification","INSUFFICIENT");out.put("stableRange",List.of());out.put("isolatedPeak",false);
            out.put("localSensitivity",null);out.put("directionConsistency",null);out.put("maxAdjacentReturnChange",null);out.put("maxAdjacentDrawdownChange",null);
            out.put("reasonCodes",List.of("MINIMUM_LOCAL_RUN_COVERAGE_NOT_MET"));return out;
        }
        var base=baseline.orElseThrow();double rt=Math.max(.02,Math.abs(base.netReturn())*.25),dt=Math.max(.02,base.maxDrawdown()*.20);
        var byOrdinal=new TreeMap<Integer,Point>();points.forEach(p->byOrdinal.put(p.ordinal(),p));
        double maxReturn=0,maxDrawdown=0;int increasing=0,decreasing=0;
        for(int i=0;i<4;i++)if(byOrdinal.containsKey(i)&&byOrdinal.containsKey(i+1)) {
            var a=byOrdinal.get(i);var b=byOrdinal.get(i+1);double change=b.netReturn()-a.netReturn();
            maxReturn=Math.max(maxReturn,Math.abs(change));maxDrawdown=Math.max(maxDrawdown,Math.abs(b.maxDrawdown()-a.maxDrawdown()));
            if(change>1e-12)increasing++;if(change< -1e-12)decreasing++;
        }
        boolean peak=base.netReturn()-byOrdinal.get(1).netReturn()>rt && base.netReturn()-byOrdinal.get(3).netReturn()>rt;
        int low=2,high=2;
        while(byOrdinal.containsKey(low-1)&&within(byOrdinal.get(low-1),base,rt,dt))low--;
        while(byOrdinal.containsKey(high+1)&&within(byOrdinal.get(high+1),base,rt,dt))high++;
        boolean stable=points.stream().allMatch(p->within(p,base,rt,dt)) && maxReturn<=rt && maxDrawdown<=dt;
        String classification=peak?"FRAGILE":stable?"STABLE":"MIXED";
        out.put("classification",classification);out.put("stableRange",List.of(byOrdinal.get(low).value(),byOrdinal.get(high).value()));out.put("isolatedPeak",peak);
        out.put("returnTolerance",rt);out.put("drawdownTolerance",dt);out.put("maxAdjacentReturnChange",maxReturn);out.put("maxAdjacentDrawdownChange",maxDrawdown);
        out.put("localSensitivity",Map.of("return",Math.max(Math.abs(byOrdinal.get(1).netReturn()-base.netReturn()),Math.abs(byOrdinal.get(3).netReturn()-base.netReturn())),
                "drawdown",Math.max(Math.abs(byOrdinal.get(1).maxDrawdown()-base.maxDrawdown()),Math.abs(byOrdinal.get(3).maxDrawdown()-base.maxDrawdown()))));
        out.put("directionConsistency",increasing==0&&decreasing==0?"FLAT":increasing==0?"DECREASING":decreasing==0?"INCREASING":"MIXED");
        out.put("reasonCodes",peak?List.of("ISOLATED_BASELINE_PEAK"):List.of());
        if("STABLE".equals(classification)&&"NEGATIVE".equals(out.get("performanceProfile")))out.put("message","参数行为稳定，但该区间整体收益为负。");
        return out;
    }
    private boolean within(Point p,Point base,double rt,double dt){return Math.abs(p.netReturn()-base.netReturn())<=rt+1e-12 && Math.abs(p.maxDrawdown()-base.maxDrawdown())<=dt+1e-12;}
}
