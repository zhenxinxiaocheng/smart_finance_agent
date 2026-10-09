package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentDataQualityIssue;
import com.smartfinance.agent.investment.entity.InvestmentDataQualitySnapshot;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentDataQualityIssueMapper;
import com.smartfinance.agent.investment.mapper.InvestmentDataQualitySnapshotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

        /** Compare the values analysis will actually use, at the existing database's precision. */
        public boolean matchesInputs(InvestmentProduct product, List<ProductDailyQuote> inputs) {
            if (records.isEmpty() || records.size() != inputs.size()) return false;
            Map<LocalDate, ProductDailyQuote> byDate = new java.util.HashMap<>();
            for (ProductDailyQuote input : inputs) if (byDate.put(input.getTradeDate(), input) != null) return false;
            boolean fund = "MUTUAL_FUND".equals(product.getProductType());
            try {
                for (Map<String, Object> record : records) {
                    ProductDailyQuote input = byDate.remove(LocalDate.parse(String.valueOf(record.get("data_date"))));
                    if (input == null) return false;
                    BigDecimal close = number(record.get("close") == null ? record.get("nav") : record.get("close"));
                    if (!same(close, input.getClosePrice(), 10)) return false;
                    if (fund) {
                        BigDecimal total = number(record.get("total_return_index"));
                        if (total == null && close != null && record.get("adjustment_factor") != null)
                            total = close.multiply(number(record.get("adjustment_factor")));
                        if (!same(total, input.getTotalReturnIndex(), 10)) return false;
                    } else if (!same(number(record.get("open")), input.getOpenPrice(), 10)
                            || !same(number(record.get("high")), input.getHighPrice(), 10)
                            || !same(number(record.get("low")), input.getLowPrice(), 10)
                            || !same(number(record.get("volume")), input.getVolume(), 8)
                            || record.containsKey("amount") && !same(number(record.get("amount")), input.getAmount(), 8)
                            || record.containsKey("turnover_rate") && !same(number(record.get("turnover_rate")), input.getTurnoverRate(), 8)) return false;
                }
                return byDate.isEmpty();
            } catch (IllegalArgumentException malformed) {
                return false;
            }
        }

        private static BigDecimal number(Object value) {
            return value == null || "null".equals(String.valueOf(value)) ? null : new BigDecimal(String.valueOf(value));
        }

        private static boolean same(BigDecimal expected, BigDecimal actual, int scale) {
            return expected == null ? actual == null : actual != null
                    && expected.setScale(scale, RoundingMode.HALF_UP).compareTo(actual.setScale(scale, RoundingMode.HALF_UP)) == 0;
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
    private InvestmentQuoteAvailabilityService availability;

    @org.springframework.beans.factory.annotation.Autowired
    public void setAvailability(InvestmentQuoteAvailabilityService availability) { this.availability = availability; }

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
            return replay(product, existing, configVersion);
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

    private Evaluation replay(InvestmentProduct product, InvestmentDataQualitySnapshot existing, String configVersion) {
        Map<String, Object> response = analysisClient.replayDataQuality(product, existing.getDatasetVersion(),
                firstSecondary(existing.getSecondaryDatasetVersionsJson()), configVersion);
        String replayRuleSet = requiredText(requiredMap(response, "qualityReport"), "qualityRuleSetVersion");
        if (!Objects.equals(existing.getQualityRuleSetVersion(), replayRuleSet)) {
            throw new IllegalStateException("数据质量规则版本发生漂移，拒绝复用旧快照");
        }
        return toEvaluation(existing, response);
    }

    /** Recheck intact preparation receipts; this method never fetches or writes quotes. */
    @Transactional
    public Evaluation preparedEvaluation(InvestmentProduct product, LocalDate startDate, LocalDate endDate) {
        String config = runtimeProperties.getDataQuality().getConfigVersion();
        for (LocalDate origin : java.util.Arrays.asList(product.getListingDate(), product.getInceptionDate()))
            if (origin != null && origin.isAfter(startDate)) startDate = origin;
        LocalDate target = availability == null ? endDate : availability.target(product, endDate, Instant.now());
        if (startDate.isAfter(target)) return null;
        Map<String, InvestmentDataQualitySnapshot> latestWindows = new LinkedHashMap<>();
        for (var receipt : snapshotMapper.selectList(contractQuery(product, adjustType(product), config)
                .le(InvestmentDataQualitySnapshot::getRequestedStartDate, target)
                .ge(InvestmentDataQualitySnapshot::getRequestedEndDate, startDate)
                .orderByDesc(InvestmentDataQualitySnapshot::getFetchedAt))) {
            latestWindows.putIfAbsent(receipt.getRequestedStartDate() + ":" + receipt.getRequestedEndDate(), receipt);
        }
        var candidates = latestWindows.values().stream().sorted(Comparator
                .comparing(InvestmentDataQualitySnapshot::getRequestedStartDate).reversed()
                .thenComparing(InvestmentDataQualitySnapshot::getRequestedEndDate)).toList();
        List<InvestmentDataQualitySnapshot> selected = new ArrayList<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(target)) {
            LocalDate next = cursor;
            var existing = candidates.stream().filter(item -> !item.getRequestedStartDate().isAfter(next)
                    && !item.getRequestedEndDate().isBefore(next)).findFirst().orElse(null);
            if (existing == null) return null;
            // Prefer newer validated inputs for each segment, including a full repair
            // followed by another incremental update. Wider failures stay outside this gate.
            var receipt = candidates.stream().filter(item -> "ALLOW".equals(item.getDecision())
                    && !item.getRequestedStartDate().isAfter(next) && !item.getRequestedEndDate().isBefore(next)
                    && item.getFetchedAt().isAfter(existing.getFetchedAt()))
                    .max(Comparator.comparing(InvestmentDataQualitySnapshot::getFetchedAt)).orElse(existing);
            selected.add(receipt);
            cursor = receipt.getRequestedEndDate().plusDays(1);
        }
        // Newer corrections inside an already requested window must participate as well.
        LocalDateTime oldest = selected.stream().map(InvestmentDataQualitySnapshot::getFetchedAt)
                .min(LocalDateTime::compareTo).orElseThrow();
        for (var receipt : candidates)
            if (!selected.contains(receipt) && receipt.getFetchedAt().isAfter(oldest)
                    && ("ALLOW".equals(receipt.getDecision()) || !receipt.getRequestedStartDate().isBefore(startDate)
                        && !receipt.getRequestedEndDate().isAfter(target)))
                selected.add(receipt);
        if (selected.size() == 1) return replay(product, selected.get(0), config);
        List<String> versions = new ArrayList<>(), secondaryVersions = new ArrayList<>();
        for (var receipt : selected) {
            Evaluation checked = replay(product, receipt, config);
            if (checked.blocked()) return checked;
            versions.add(checked.datasetVersion());
            String secondary = firstSecondary(receipt.getSecondaryDatasetVersionsJson());
            if (secondary != null) secondaryVersions.add(secondary);
        }
        Map<String,Object> response = analysisClient.replayDataQuality(product, versions, secondaryVersions,
                config, startDate, target);
        String rules = requiredText(requiredMap(response, "qualityReport"), "qualityRuleSetVersion");
        if (selected.stream().anyMatch(item -> !Objects.equals(item.getQualityRuleSetVersion(), rules)))
            throw new IllegalStateException("数据质量规则版本发生漂移，拒绝复用旧快照");
        Map<String,Object> manifest = new LinkedHashMap<>(requiredMap(response, "manifest"));
        manifest.put("inputDatasetVersions", versions);
        response = new LinkedHashMap<>(response);
        response.put("manifest", manifest);
        // Claim the derived immutable snapshot before persisting its existing receipt format.
        analysisClient.claimDataQuality(requiredText(response, "datasetVersion"), config);
        for (String secondary : stringList(response.get("secondaryDatasetVersions")))
            analysisClient.claimDataQuality(secondary, config);
        return toEvaluation(persist(config, response), response);
    }

    /** Analysis gates use the dataset's own request contract, never an unrelated latest window. */
    public Map<String, Object> analysisStatus(InvestmentProduct product, String datasetVersion) {
        if (datasetVersion == null) return latestStatus(product);
        String config = runtimeProperties.getDataQuality().getConfigVersion();
        InvestmentDataQualitySnapshot source = snapshotMapper.selectOne(
                contractQuery(product, adjustType(product), config)
                        .eq(InvestmentDataQualitySnapshot::getDatasetVersion, datasetVersion).last("LIMIT 1"));
        if (source == null) return status(product, null);
        return status(product, findExact(product, source.getRequestedStartDate(), source.getRequestedEndDate(),
                source.getAdjustType(), config));
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
        return status(product, snapshot);
    }

    private Map<String, Object> status(InvestmentProduct product, InvestmentDataQualitySnapshot snapshot) {
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
        result.put("sampleStartDate", snapshot.getSampleStartDate());
        result.put("sampleEndDate", snapshot.getSampleEndDate());
        result.put("evaluatedAt", snapshot.getEvaluatedAt());
        result.put("issues", issues.stream().map(this::issueView).toList());
        return result;
    }

    private InvestmentDataQualitySnapshot findExact(InvestmentProduct product,
                                                     LocalDate startDate,
                                                     LocalDate endDate,
                                                     String adjustType,
                                                     String configVersion) {
        return snapshotMapper.selectOne(contractQuery(product, adjustType, configVersion)
                .eq(InvestmentDataQualitySnapshot::getRequestedStartDate, startDate)
                .eq(InvestmentDataQualitySnapshot::getRequestedEndDate, endDate)
                .orderByDesc(InvestmentDataQualitySnapshot::getFetchedAt)
                .last("LIMIT 1"));
    }

    private LambdaQueryWrapper<InvestmentDataQualitySnapshot> contractQuery(
            InvestmentProduct product, String adjustType, String configVersion) {
        return new LambdaQueryWrapper<InvestmentDataQualitySnapshot>()
                .eq(InvestmentDataQualitySnapshot::getProductType, product.getProductType())
                .eq(InvestmentDataQualitySnapshot::getCode, product.getCode())
                .eq(InvestmentDataQualitySnapshot::getMarket, product.getMarket())
                .eq(InvestmentDataQualitySnapshot::getFrequency,
                        runtimeProperties.getDataQuality().getFrequency())
                .eq(InvestmentDataQualitySnapshot::getAdjustType, adjustType)
                .eq(InvestmentDataQualitySnapshot::getQualityConfigVersion, configVersion);
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

    public String adjustType(InvestmentProduct product) {
        return adjustType(product.getProductType(), product.getMarket());
    }

    /**
     * 基金、ETF、指数及境外股票采用原始价格；A 股研究价格按配置复权。
     */
    public String adjustType(String productType, String market) {
        if (Set.of("MUTUAL_FUND", "FUND", "ETF", "INDEX").contains(productType)) {
            return runtimeProperties.getDataQuality().getFundAdjustType();
        }
        if (("ETF".equals(productType) || "STOCK".equals(productType)) && market != null
                && Set.of("NASDAQ", "NYSE", "AMEX").contains(market)) {
            return runtimeProperties.getDataQuality().getFundAdjustType();
        }
        return runtimeProperties.getDataQuality().getStockAdjustType();
    }

    /** 该产品的数据序列类型：基金为净值，其余为价格。 */
    String datasetType(InvestmentProduct product) {
        return ("MUTUAL_FUND".equals(product.getProductType())
                || "FUND".equals(product.getProductType())) ? "NAV" : "PRICE";
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
