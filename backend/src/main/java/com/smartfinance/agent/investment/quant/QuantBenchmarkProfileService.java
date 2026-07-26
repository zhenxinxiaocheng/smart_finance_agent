package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QuantBenchmarkProfileService {
    private static final TypeReference<List<Map<String, Object>>> RECORDS_TYPE =
            new TypeReference<>() { };

    private final BenchmarkProfileMapper benchmarkMapper;
    private final QuantBenchmarkSnapshotMapper snapshotMapper;
    private final AnalysisServiceClient analysisClient;
    private final ObjectMapper objectMapper;

    public QuantBenchmarkProfileService(BenchmarkProfileMapper benchmarkMapper,
                                        QuantBenchmarkSnapshotMapper snapshotMapper,
                                        AnalysisServiceClient analysisClient,
                                        ObjectMapper objectMapper) {
        this.benchmarkMapper = benchmarkMapper;
        this.snapshotMapper = snapshotMapper;
        this.analysisClient = analysisClient;
        this.objectMapper = objectMapper;
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
        QuantBenchmarkSnapshot cached = cachedSnapshot(profile.getId(), startDate, endDate);
        if (cached != null) {
            return resolved(profile, cached, readRecords(cached.getRecordsJson()), startDate, endDate);
        }
        try {
            Map<String, Object> response = analysisClient.benchmarkHistory(
                    profile.getBenchmarkCode(),
                    startDate,
                    endDate
            );
            List<Map<String, Object>> records = records(response.get("records"));
            if (records.size() < 2) {
                return ResolvedBenchmark.unavailable("官方基准在训练区间内无可用行情");
            }
            QuantBenchmarkSnapshot snapshot = persistSnapshot(profile, response, records);
            return resolved(profile, snapshot, readRecords(snapshot.getRecordsJson()), startDate, endDate);
        } catch (RuntimeException exception) {
            return ResolvedBenchmark.unavailable("官方基准数据获取失败：" + concise(exception.getMessage()));
        }
    }

    private QuantBenchmarkSnapshot cachedSnapshot(Long profileId,
                                                   LocalDate startDate,
                                                   LocalDate endDate) {
        return snapshotMapper.selectOne(
                new LambdaQueryWrapper<QuantBenchmarkSnapshot>()
                        .eq(QuantBenchmarkSnapshot::getBenchmarkProfileId, profileId)
                        .le(QuantBenchmarkSnapshot::getSampleStartDate, startDate)
                        .ge(QuantBenchmarkSnapshot::getSampleEndDate, endDate)
                        .orderByDesc(QuantBenchmarkSnapshot::getFetchedAt)
                        .last("LIMIT 1")
        );
    }

    private QuantBenchmarkSnapshot persistSnapshot(BenchmarkProfile profile,
                                                    Map<String, Object> response,
                                                    List<Map<String, Object>> records) {
        List<Map<String, Object>> ordered = records.stream()
                .sorted((left, right) -> recordDate(left).compareTo(recordDate(right)))
                .toList();
        String recordsJson = writeRecords(ordered);
        LinkedHashMap<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("benchmarkProfileId", profile.getId());
        fingerprint.put("benchmarkCode", profile.getBenchmarkCode());
        fingerprint.put("sourceVersion", profile.getSourceVersion());
        fingerprint.put("currency", profile.getCurrency());
        fingerprint.put("fxRule", profile.getFxRule());
        fingerprint.put("records", ordered);
        String snapshotVersion = sha256(writeJson(fingerprint));
        QuantBenchmarkSnapshot existing = snapshotMapper.selectOne(
                new LambdaQueryWrapper<QuantBenchmarkSnapshot>()
                        .eq(QuantBenchmarkSnapshot::getSnapshotVersion, snapshotVersion)
                        .last("LIMIT 1")
        );
        if (existing != null) return existing;

        QuantBenchmarkSnapshot snapshot = new QuantBenchmarkSnapshot();
        snapshot.setSnapshotVersion(snapshotVersion);
        snapshot.setBenchmarkProfileId(profile.getId());
        snapshot.setBenchmarkCode(profile.getBenchmarkCode());
        snapshot.setSourceUri(profile.getSourceUri());
        snapshot.setSourceVersion(profile.getSourceVersion());
        snapshot.setProvider(text(response.get("provider")));
        snapshot.setAdapterVersion(text(response.get("adapterVersion")));
        snapshot.setCurrency(profile.getCurrency());
        snapshot.setFxRule(profile.getFxRule());
        snapshot.setEffectiveFrom(profile.getEffectiveFrom());
        snapshot.setEffectiveTo(profile.getEffectiveTo());
        snapshot.setSampleStartDate(recordDate(ordered.get(0)));
        snapshot.setSampleEndDate(recordDate(ordered.get(ordered.size() - 1)));
        snapshot.setFetchedAt(parseFetchedAt(response.get("fetchedAt")));
        snapshot.setRecordsJson(recordsJson);
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    private ResolvedBenchmark resolved(BenchmarkProfile profile,
                                       QuantBenchmarkSnapshot snapshot,
                                       List<Map<String, Object>> records,
                                       LocalDate startDate,
                                       LocalDate endDate) {
        List<Map<String, Object>> selected = records.stream()
                .filter(record -> {
                    LocalDate date = recordDate(record);
                    return !date.isBefore(startDate) && !date.isAfter(endDate);
                })
                .toList();
        if (selected.size() < 2) {
            return ResolvedBenchmark.unavailable("官方基准快照未覆盖完整训练区间");
        }
        return new ResolvedBenchmark(
                true,
                profile.getBenchmarkCode(),
                profile.getModelFamily(),
                snapshot.getSnapshotVersion(),
                selected,
                null,
                null
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private List<Map<String, Object>> readRecords(String value) {
        try {
            return objectMapper.readValue(value, RECORDS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("官方基准快照内容无法读取", exception);
        }
    }

    private String writeRecords(List<Map<String, Object>> records) {
        return writeJson(records);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("官方基准快照无法保存", exception);
        }
    }

    private static LocalDate recordDate(Map<String, Object> record) {
        Object value = record.get("data_date");
        if (value == null) value = record.get("date");
        if (value == null) throw new IllegalStateException("官方基准记录缺少日期");
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    private static LocalDateTime parseFetchedAt(Object value) {
        if (value == null) return LocalDateTime.now();
        try {
            return OffsetDateTime.parse(String.valueOf(value)).toLocalDateTime();
        } catch (RuntimeException ignored) {
            return LocalDateTime.now();
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持SHA-256", exception);
        }
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
