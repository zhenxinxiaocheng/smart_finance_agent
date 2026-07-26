package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class QuantBenchmarkProfileService {
    private final BenchmarkProfileMapper benchmarkMapper;
    private final AnalysisServiceClient analysisClient;

    public QuantBenchmarkProfileService(BenchmarkProfileMapper benchmarkMapper,
                                        AnalysisServiceClient analysisClient) {
        this.benchmarkMapper = benchmarkMapper;
        this.analysisClient = analysisClient;
    }

    public ResolvedBenchmark resolve(String productType,
                                     String productCode,
                                     LocalDate asOfDate,
                                     LocalDate startDate,
                                     LocalDate endDate) {
        List<BenchmarkProfile> profiles = benchmarkMapper.selectList(
                new LambdaQueryWrapper<BenchmarkProfile>()
                        .eq(BenchmarkProfile::getProductType, productType)
                        .eq(BenchmarkProfile::getActive, true)
                        .le(BenchmarkProfile::getEffectiveFrom, asOfDate)
                        .and(query -> query.isNull(BenchmarkProfile::getEffectiveTo)
                                .or()
                                .ge(BenchmarkProfile::getEffectiveTo, asOfDate))
                        .orderByDesc(BenchmarkProfile::getEffectiveFrom)
        );
        BenchmarkProfile profile = profiles.stream()
                .filter(item -> productCode.equals(item.getProductCode()))
                .findFirst()
                .orElseGet(() -> profiles.stream()
                        .filter(item -> item.getProductCode() == null || item.getProductCode().isBlank())
                        .findFirst()
                        .orElse(null));
        if (profile == null) {
            return ResolvedBenchmark.unavailable("未配置当前资产的版本化官方基准");
        }
        try {
            Map<String, Object> response = analysisClient.benchmarkHistory(
                    profile.getBenchmarkCode(),
                    startDate,
                    endDate
            );
            List<Map<String, Object>> records = records(response.get("records"));
            if (records.isEmpty()) {
                return ResolvedBenchmark.unavailable("官方基准在训练区间内无可用行情");
            }
            return new ResolvedBenchmark(
                    true,
                    profile.getBenchmarkCode(),
                    profile.getModelFamily(),
                    profile.getSourceVersion(),
                    records,
                    null,
                    null
            );
        } catch (RuntimeException exception) {
            return ResolvedBenchmark.unavailable("官方基准数据获取失败：" + concise(exception.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static String concise(String message) {
        if (message == null || message.isBlank()) return "未知错误";
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    public record ResolvedBenchmark(boolean available,
                                    String benchmarkCode,
                                    String modelFamily,
                                    String sourceVersion,
                                    List<Map<String, Object>> records,
                                    String failureCode,
                                    String failureSummary) {
        private static ResolvedBenchmark unavailable(String summary) {
            return new ResolvedBenchmark(
                    false,
                    null,
                    null,
                    null,
                    List.of(),
                    "BENCHMARK_UNAVAILABLE",
                    summary
            );
        }
    }
}
