package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentDataQualityIssue;
import com.smartfinance.agent.investment.entity.InvestmentDataQualitySnapshot;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentDataQualityIssueMapper;
import com.smartfinance.agent.investment.mapper.InvestmentDataQualitySnapshotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class InvestmentDataQualityService {

    public record Evaluation(InvestmentDataQualitySnapshot snapshot,
                             Map<String, Object> response,
                             List<Map<String, Object>> records,
                             List<String> secondaryDatasetVersions) {
        public String datasetVersion() {
            return snapshot.getDatasetVersion();
        }

        public String status() {
            return snapshot.getQualityStatus();
        }

        public String ruleSetVersion() {
            return snapshot.getQualityRuleSetVersion();
        }

        public boolean blocked() {
            return "BLOCK".equals(snapshot.getDecision());
        }

        public boolean failedRule(String ruleCode) {
            Object report = response.get("qualityReport");
            if (!(report instanceof Map<?, ?> reportMap)
                    || !(reportMap.get("issues") instanceof List<?> issues)) {
                return false;
            }
            return issues.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .anyMatch(issue -> ruleCode.equals(issue.get("ruleCode"))
                            && "FAIL".equals(issue.get("outcome")));
        }
    }

    private final InvestmentDataQualitySnapshotMapper snapshotMapper;
    private final InvestmentDataQualityIssueMapper issueMapper;
    private final AnalysisServiceClient analysisClient;
    private final InvestmentRuntimeProperties runtimeProperties;
    private final ObjectMapper objectMapper;

    public InvestmentDataQualityService(InvestmentDataQualitySnapshotMapper snapshotMapper,
                                        InvestmentDataQualityIssueMapper issueMapper,
                                        AnalysisServiceClient analysisClient,
                                        InvestmentRuntimeProperties runtimeProperties,
                                        ObjectMapper objectMapper) {
        this.snapshotMapper = snapshotMapper;
        this.issueMapper = issueMapper;
        this.analysisClient = analysisClient;
        this.runtimeProperties = runtimeProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Evaluation resolve(InvestmentProduct product,
                              LocalDate startDate,
                              LocalDate endDate,
                              boolean fetchFreshData) {
        return resolve(product, startDate, endDate, adjustType(product), fetchFreshData);
    }

    @Transactional
    public Evaluation resolve(InvestmentProduct product, LocalDate startDate, LocalDate endDate,
                              String adjustType, boolean fetchFreshData) {
        String configVersion = runtimeProperties.getDataQuality().getConfigVersion();
        InvestmentDataQualitySnapshot existing = findExact(
                product, startDate, endDate, adjustType, configVersion);
        if (existing != null && !fetchFreshData) {
            String secondary = firstSecondary(existing.getSecondaryDatasetVersionsJson());
            Map<String, Object> response = analysisClient.replayDataQuality(
                    existing.getDatasetVersion(), secondary, configVersion);
            Evaluation replayed = toEvaluation(existing, response);
            if (!Objects.equals(existing.getQualityRuleSetVersion(), replayed.ruleSetVersion())) {
                throw new IllegalStateException("数据质量规则版本发生漂移，拒绝复用旧快照");
            }
            return replayed;
        }

        Map<String, Object> response = analysisClient.validateDataQuality(
                product,
                startDate,
                endDate,
                runtimeProperties.getDataQuality().getFrequency(),
                adjustType,
                configVersion);
        InvestmentDataQualitySnapshot persisted = persist(configVersion, response);
        return toEvaluation(persisted, response);
    }

    public void claim(Evaluation evaluation) {
        analysisClient.claimDataQuality(
                evaluation.datasetVersion(), runtimeProperties.getDataQuality().getConfigVersion());
        for (String secondaryDatasetVersion : evaluation.secondaryDatasetVersions()) {
            analysisClient.claimDataQuality(
                    secondaryDatasetVersion, runtimeProperties.getDataQuality().getConfigVersion());
        }
    }

    public Map<String, Object> latestStatus(InvestmentProduct product) {
        InvestmentDataQualitySnapshot snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<InvestmentDataQualitySnapshot>()
                        .eq(InvestmentDataQualitySnapshot::getProductType, product.getProductType())
                        .eq(InvestmentDataQualitySnapshot::getCode, product.getCode())
                        .eq(InvestmentDataQualitySnapshot::getMarket, product.getMarket())
                        .eq(InvestmentDataQualitySnapshot::getAdjustType, adjustType(product))
                        .orderByDesc(InvestmentDataQualitySnapshot::getEvaluatedAt)
                        .last("LIMIT 1"));
        if (snapshot == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("productType", product.getProductType());
            result.put("code", product.getCode());
            result.put("market", product.getMarket());
            result.put("status", "NOT_EVALUATED");
            return result;
        }
        List<InvestmentDataQualityIssue> issues = issueMapper.selectList(
                new LambdaQueryWrapper<InvestmentDataQualityIssue>()
                        .eq(InvestmentDataQualityIssue::getQualitySnapshotId, snapshot.getId())
                        .orderByAsc(InvestmentDataQualityIssue::getSequenceNo));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productType", snapshot.getProductType());
        result.put("code", snapshot.getCode());
        result.put("market", snapshot.getMarket());
        result.put("datasetVersion", snapshot.getDatasetVersion());
        result.put("provider", snapshot.getProvider());
        result.put("adapterVersion", snapshot.getAdapterVersion());
        result.put("qualityConfigVersion", snapshot.getQualityConfigVersion());
        result.put("qualityRuleSetVersion", snapshot.getQualityRuleSetVersion());
        result.put("status", snapshot.getQualityStatus());
        result.put("decision", snapshot.getDecision());
        result.put("adjustType", snapshot.getAdjustType());
        result.put("requestedStartDate", snapshot.getRequestedStartDate());
        result.put("requestedEndDate", snapshot.getRequestedEndDate());
        result.put("evaluatedAt", snapshot.getEvaluatedAt());
        result.put("issues", issues.stream().map(this::issueView).toList());
        return result;
    }

    private InvestmentDataQualitySnapshot findExact(InvestmentProduct product,
                                                     LocalDate startDate,
                                                     LocalDate endDate,
                                                     String adjustType,
                                                     String configVersion) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<InvestmentDataQualitySnapshot>()
                .eq(InvestmentDataQualitySnapshot::getProductType, product.getProductType())
                .eq(InvestmentDataQualitySnapshot::getCode, product.getCode())
                .eq(InvestmentDataQualitySnapshot::getMarket, product.getMarket())
                .eq(InvestmentDataQualitySnapshot::getFrequency,
                        runtimeProperties.getDataQuality().getFrequency())
                .eq(InvestmentDataQualitySnapshot::getAdjustType, adjustType)
                .eq(InvestmentDataQualitySnapshot::getQualityConfigVersion, configVersion)
                .eq(InvestmentDataQualitySnapshot::getRequestedStartDate, startDate)
                .eq(InvestmentDataQualitySnapshot::getRequestedEndDate, endDate)
                .orderByDesc(InvestmentDataQualitySnapshot::getFetchedAt)
                .last("LIMIT 1"));
    }

    private InvestmentDataQualitySnapshot persist(String configVersion,
                                                   Map<String, Object> response) {
        Map<String, Object> manifest = requiredMap(response, "manifest");
        Map<String, Object> report = requiredMap(response, "qualityReport");
        String datasetVersion = requiredText(response, "datasetVersion");
        InvestmentDataQualitySnapshot snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<InvestmentDataQualitySnapshot>()
                        .eq(InvestmentDataQualitySnapshot::getDatasetVersion, datasetVersion)
                        .eq(InvestmentDataQualitySnapshot::getQualityConfigVersion, configVersion));
        if (snapshot == null) {
            snapshot = new InvestmentDataQualitySnapshot();
            snapshot.setDatasetVersion(datasetVersion);
        }
        snapshot.setProductType(requiredText(manifest, "productType"));
        snapshot.setCode(requiredText(manifest, "code"));
        snapshot.setMarket(requiredText(manifest, "market"));
        snapshot.setFrequency(requiredText(manifest, "frequency"));
        snapshot.setAdjustType(requiredText(manifest, "adjustType"));
        snapshot.setProvider(requiredText(manifest, "provider"));
        snapshot.setAdapterVersion(requiredText(manifest, "adapterVersion"));
        snapshot.setQualityConfigVersion(configVersion);
        snapshot.setQualityRuleSetVersion(requiredText(report, "qualityRuleSetVersion"));
        snapshot.setQualityStatus(requiredText(report, "status"));
        snapshot.setDecision(requiredText(report, "decision"));
        snapshot.setEnforcementMode(requiredText(report, "enforcementMode"));
        snapshot.setRequestedStartDate(LocalDate.parse(requiredText(manifest, "requestedStartDate")));
        snapshot.setRequestedEndDate(LocalDate.parse(requiredText(manifest, "requestedEndDate")));
        snapshot.setSampleStartDate(LocalDate.parse(requiredText(manifest, "sampleStartDate")));
        snapshot.setSampleEndDate(LocalDate.parse(requiredText(manifest, "sampleEndDate")));
        snapshot.setFetchedAt(timestamp(requiredText(manifest, "fetchedAt")));
        snapshot.setEvaluatedAt(timestamp(requiredText(report, "evaluatedAt")));
        snapshot.setManifestJson(writeJson(manifest));
        snapshot.setReportJson(writeJson(report));
        snapshot.setSecondaryDatasetVersionsJson(writeJson(
                response.getOrDefault("secondaryDatasetVersions", List.of())));
        if (snapshot.getId() == null) {
            snapshotMapper.insert(snapshot);
        } else {
            snapshotMapper.updateById(snapshot);
            issueMapper.delete(new LambdaQueryWrapper<InvestmentDataQualityIssue>()
                    .eq(InvestmentDataQualityIssue::getQualitySnapshotId, snapshot.getId()));
        }
        persistIssues(snapshot.getId(), report);
        return snapshot;
    }

    private void persistIssues(Long snapshotId, Map<String, Object> report) {
        List<Map<String, Object>> issues = mapList(report.get("issues"));
        for (int index = 0; index < issues.size(); index++) {
            Map<String, Object> value = issues.get(index);
            InvestmentDataQualityIssue issue = new InvestmentDataQualityIssue();
            issue.setQualitySnapshotId(snapshotId);
            issue.setSequenceNo(index);
            issue.setRuleCode(requiredText(value, "ruleCode"));
            issue.setSeverity(requiredText(value, "severity"));
            issue.setOutcome(requiredText(value, "outcome"));
            issue.setMessage(requiredText(value, "message"));
            issue.setObservedJson(nullableJson(value.get("observed")));
            issue.setExpectedJson(nullableJson(value.get("expected")));
            issue.setAffectedDatesJson(nullableJson(value.get("affectedDates")));
            issueMapper.insert(issue);
        }
    }

    private Evaluation toEvaluation(InvestmentDataQualitySnapshot persisted,
                                    Map<String, Object> response) {
        Map<String, Object> report = requiredMap(response, "qualityReport");
        persisted.setQualityRuleSetVersion(requiredText(report, "qualityRuleSetVersion"));
        persisted.setQualityStatus(requiredText(report, "status"));
        persisted.setDecision(requiredText(report, "decision"));
        return new Evaluation(
                persisted,
                response,
                mapList(response.get("records")),
                stringList(response.get("secondaryDatasetVersions")));
    }

    private Map<String, Object> issueView(InvestmentDataQualityIssue issue) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleCode", issue.getRuleCode());
        result.put("severity", issue.getSeverity());
        result.put("outcome", issue.getOutcome());
        result.put("message", issue.getMessage());
        result.put("observed", readJson(issue.getObservedJson()));
        result.put("expected", readJson(issue.getExpectedJson()));
        result.put("affectedDates", readJson(issue.getAffectedDatesJson()));
        return result;
    }

    String adjustType(InvestmentProduct product) {
        return ("MUTUAL_FUND".equals(product.getProductType())
                || "FUND".equals(product.getProductType()))
                ? runtimeProperties.getDataQuality().getFundAdjustType()
                : runtimeProperties.getDataQuality().getStockAdjustType();
    }

    private String firstSecondary(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<String> versions = objectMapper.readValue(json, new TypeReference<>() { });
            return versions.isEmpty() ? null : versions.get(0);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("次级数据版本记录损坏", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("数据质量响应缺少 " + key);
        }
        return (Map<String, Object>) map;
    }

    private static String requiredText(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("数据质量响应缺少 " + key);
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        }
        return List.copyOf(result);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static LocalDateTime timestamp(String value) {
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (RuntimeException ignored) {
            return LocalDateTime.parse(value);
        }
    }

    private String nullableJson(Object value) {
        return value == null ? null : writeJson(value);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据质量结果序列化失败", exception);
        }
    }

    private Object readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            return value;
        }
    }
}
