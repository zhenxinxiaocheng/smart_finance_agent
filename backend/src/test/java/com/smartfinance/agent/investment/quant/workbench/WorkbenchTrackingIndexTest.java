package com.smartfinance.agent.investment.quant.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.quant.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkbenchTrackingIndexTest {
    private final QuantBenchmarkProfileService profiles = mock(QuantBenchmarkProfileService.class);
    private final WorkbenchTrackingIndex tracking = new WorkbenchTrackingIndex(profiles, new ObjectMapper());
    private final LocalDate start = LocalDate.parse("2023-01-01"), end = LocalDate.parse("2023-03-31");
    private Map<String,Object> asset(String code) { return Map.of("product_type", "MUTUAL_FUND", "code", code); }
    private BenchmarkProfile profile(String code, String target) {
        var p = new BenchmarkProfile(); p.setProductCode(code); p.setModelFamily("INDEX_FUND");
        p.setBenchmarkCode(target); p.setDisplayName(target); p.setCurrency("CNY");
        p.setCompositionJson("{\"" + target + "\":1}"); return p;
    }
    @Test void sameExplicitTargetUsesExistingSnapshotAndFreezesRecords() {
        when(profiles.configuration(eq("MUTUAL_FUND"), anyString(), eq(start)))
            .thenAnswer(i -> profile(i.getArgument(1), "NASDAQ100"));
        when(profiles.resolveCachedComparison("MUTUAL_FUND", "A", start, end)).thenReturn(
            new QuantBenchmarkProfileService.ResolvedBenchmark(true,"NASDAQ100","INDEX_FUND","snapshot-v1",
                List.of(Map.of("data_date","2022-12-30","close",100), Map.of("data_date","2023-03-31","close",120)),null,null));
        var result = tracking.resolve(List.of(asset("A"), asset("B")), start, end);
        assertThat(result).containsEntry("status","READY").containsEntry("sourceVersion","snapshot-v1");
        assertThat((List<?>)result.get("records")).hasSize(2);
    }
    @Test void differentMissingOrNonIndexTargetsNeverCreateComposite() {
        when(profiles.configuration("MUTUAL_FUND", "A", start)).thenReturn(profile("A", "CSI300"));
        when(profiles.configuration("MUTUAL_FUND", "B", start)).thenReturn(profile("B", "NASDAQ100"));
        assertThat(tracking.resolve(List.of(asset("A"),asset("B")),start,end)).isEmpty();
        assertThat(tracking.resolve(List.of(asset("A"),asset("C")),start,end)).isEmpty();
        assertThat(tracking.resolve(List.of(Map.of("product_type","STOCK","code","X")),start,end)).isEmpty();
        var active = profile("A","CSI300"); active.setModelFamily("ACTIVE_EQUITY_FUND");
        when(profiles.configuration("MUTUAL_FUND","A",start)).thenReturn(active);
        assertThat(tracking.resolve(List.of(asset("A")),start,end)).isEmpty();
        verify(profiles, never()).resolveCachedComparison(anyString(),anyString(),any(),any());
    }
    @Test void fallbackCompositeAndUnavailableHistoryAreNotTrackingTargets() {
        var p = profile(null,"CSI300");
        when(profiles.configuration("MUTUAL_FUND","A",start)).thenReturn(p);
        assertThat(tracking.resolve(List.of(asset("A")),start,end)).isEmpty();
        p.setProductCode("A"); p.setCompositionJson("{\"CSI300\":0.5,\"NASDAQ100\":0.5}");
        assertThat(tracking.resolve(List.of(asset("A")),start,end)).isEmpty();
        p.setCompositionJson("{\"CSI300\":1}");
        when(profiles.resolveCachedComparison(anyString(),anyString(),any(),any())).thenThrow(new IllegalStateException("provider details"));
        assertThat(tracking.resolve(List.of(asset("A")),start,end)).isEmpty();
    }
}
