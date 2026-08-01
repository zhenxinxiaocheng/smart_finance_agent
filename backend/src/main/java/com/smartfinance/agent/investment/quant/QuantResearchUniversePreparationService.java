package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.InvestmentDataQualityService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QuantResearchUniversePreparationService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final QuantResearchUniverseMapper universeMapper;
    private final QuantUniverseMembershipMapper membershipMapper;
    private final QuantBenchmarkProfileService benchmarkProfileService;
    private final InvestmentDataQualityService dataQualityService;
    private final AnalysisServiceClient analysisClient;
    private final ObjectMapper objectMapper;

    public QuantResearchUniversePreparationService(
            InvestmentProductMapper productMapper,
            ProductDailyQuoteMapper quoteMapper,
            QuantResearchUniverseMapper universeMapper,
            QuantUniverseMembershipMapper membershipMapper,
            QuantBenchmarkProfileService benchmarkProfileService,
            InvestmentDataQualityService dataQualityService,
            AnalysisServiceClient analysisClient,
            ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.universeMapper = universeMapper;
        this.membershipMapper = membershipMapper;
        this.benchmarkProfileService = benchmarkProfileService;
        this.dataQualityService = dataQualityService;
        this.analysisClient = analysisClient;
        this.objectMapper = objectMapper;
    }

    public int prepare(InvestmentDataJob job) {
        if (!"RESEARCH_UNIVERSE_HISTORY".equals(job.getJobType())) {
            throw new IllegalArgumentException("当前任务不是研究资产池准备任务");
        }
        InvestmentProduct target = requireProduct(job.getProductId());
        List<ProductDailyQuote> targetQuotes = quotes(target.getId());
        if (targetQuotes.size() < 2) {
            throw new IllegalStateException("目标资产历史数据不足，无法准备研究资产池");
        }
        LocalDate targetStartDate = targetQuotes.get(0).getTradeDate();
        LocalDate endDate = targetQuotes.get(targetQuotes.size() - 1).getTradeDate();
        BenchmarkProfile profile = benchmarkProfileService.configuration(
                target.getProductType(), target.getCode(), endDate);
        if (profile == null) {
            throw new IllegalStateException("尚未配置该资产的官方基准");
        }
        DiscoveryConfiguration discovery = discoveryConfiguration(
                profile.getModelFamily(), profile.getBenchmarkCode());
        LocalDate startDate = QuantResearchHistoryPolicy.resolveStartDate(
                discovery.rule(), profile.getEffectiveFrom(), targetStartDate);
        Map<String, Object> response = analysisClient.researchFundUniverse(
                profile.getModelFamily(),
                profile.getBenchmarkCode(),
                target.getCode(),
                startDate,
                endDate,
                discovery.memberLimit(),
                discovery.minimumRecords(),
                discovery.rule()
        );
        List<Map<String, Object>> discovered = mapList(response.get("members"));
        QuantResearchUniverse universe = upsertUniverse(profile, discovery);
        List<Map<String, Object>> acceptedVersions = new ArrayList<>();
        for (Map<String, Object> member : discovered) {
            InvestmentProduct product = upsertProduct(member);
            LocalDate memberStartDate = QuantResearchHistoryPolicy.memberStartDate(
                    startDate,
                    mapList(member.get("records"))
            );
            InvestmentDataQualityService.Evaluation quality =
                    dataQualityService.resolve(product, memberStartDate, endDate, false);
            if (quality.blocked() || quality.records().size() < discovery.minimumRecords()) {
                continue;
            }
            dataQualityService.claim(quality);
            persistQuotes(product, quality.response());
            upsertMembership(universe, product, quality);
            acceptedVersions.add(Map.of(
                    "code", product.getCode(),
                    "datasetVersion", quality.datasetVersion()
            ));
        }
        if (acceptedVersions.size() < discovery.minimumMembers()) {
            throw new IllegalStateException(
                    "同类历史资产仅准备完成 " + acceptedVersions.size()
                            + " 个，低于最低要求 " + discovery.minimumMembers() + " 个"
            );
        }
        Map<String, Object> versionMaterial = new LinkedHashMap<>();
        versionMaterial.put("modelFamily", profile.getModelFamily());
        versionMaterial.put("benchmarkCode", profile.getBenchmarkCode());
        versionMaterial.put("benchmarkSourceVersion", profile.getSourceVersion());
        versionMaterial.put("selectionRule", discovery.rule());
        versionMaterial.put("members", acceptedVersions);
        universe.setDatasetVersion(QuantExperimentFingerprint.canonicalHash(versionMaterial));
        universe.setUpdatedAt(LocalDateTime.now());
        universeMapper.updateById(universe);
        return acceptedVersions.size();
    }

    public LocalDate requiredStartDate(InvestmentProduct product,
                                       LocalDate targetStartDate,
                                       LocalDate asOfDate) {
        BenchmarkProfile profile = benchmarkProfileService.configuration(
                product.getProductType(), product.getCode(), asOfDate);
        if (profile == null) return targetStartDate;
        DiscoveryConfiguration discovery = findDiscoveryConfiguration(
                profile.getModelFamily(), profile.getBenchmarkCode());
        Map<String, Object> rule = discovery == null ? Map.of() : discovery.rule();
        return QuantResearchHistoryPolicy.resolveStartDate(
                rule, profile.getEffectiveFrom(), targetStartDate);
    }

    public QuantResearchUniverse findReadyUniverse(String modelFamily,
                                                   String benchmarkCode,
                                                   String benchmarkSourceVersion) {
        String code = universeCode(modelFamily, benchmarkCode, benchmarkSourceVersion);
        QuantResearchUniverse universe = universeMapper.selectOne(
                new LambdaQueryWrapper<QuantResearchUniverse>()
                        .eq(QuantResearchUniverse::getUniverseCode, code)
                        .eq(QuantResearchUniverse::getActive, true)
                        .last("LIMIT 1")
        );
        if (universe == null || universe.getDatasetVersion() == null
                || universe.getDatasetVersion().isBlank()) {
            return null;
        }
        DiscoveryConfiguration configuration = discoveryConfiguration(modelFamily, benchmarkCode);
        if (!QuantResearchUniverseRuleVersion.matches(
                readJson(universe.getSelectionRuleJson()),
                configuration.rule(),
                benchmarkCode,
                benchmarkSourceVersion
        )) {
            return null;
        }
        Long count = membershipMapper.selectCount(
                new LambdaQueryWrapper<QuantUniverseMembership>()
                        .eq(QuantUniverseMembership::getUniverseId, universe.getId())
                        .isNull(QuantUniverseMembership::getValidTo)
        );
        return count >= configuration.minimumMembers() ? universe : null;
    }

    private QuantResearchUniverse upsertUniverse(BenchmarkProfile profile,
                                                 DiscoveryConfiguration discovery) {
        String code = universeCode(
                profile.getModelFamily(), profile.getBenchmarkCode(), profile.getSourceVersion());
        QuantResearchUniverse universe = universeMapper.selectOne(
                new LambdaQueryWrapper<QuantResearchUniverse>()
                        .eq(QuantResearchUniverse::getUniverseCode, code)
                        .last("LIMIT 1")
        );
        if (universe == null) {
            universe = new QuantResearchUniverse();
            universe.setUniverseCode(code);
            universe.setCreatedAt(LocalDateTime.now());
        }
        universe.setName(profile.getDisplayName() + "同类历史资产池");
        universe.setModelFamily(profile.getModelFamily());
        universe.setMarket("CN");
        Map<String, Object> storedRule = new LinkedHashMap<>(discovery.rule());
        storedRule.put("benchmarkCode", profile.getBenchmarkCode());
        storedRule.put("benchmarkSourceVersion", profile.getSourceVersion());
        universe.setSelectionRuleJson(writeJson(storedRule));
        universe.setActive(true);
        universe.setUpdatedAt(LocalDateTime.now());
        if (universe.getId() == null) universeMapper.insert(universe);
        else universeMapper.updateById(universe);
        return universe;
    }

    private DiscoveryConfiguration discoveryConfiguration(String modelFamily,
                                                          String benchmarkCode) {
        DiscoveryConfiguration configuration =
                findDiscoveryConfiguration(modelFamily, benchmarkCode);
        if (configuration != null) return configuration;
        throw new IllegalStateException("尚未配置该官方基准的同类资产发现规则");
    }

    private DiscoveryConfiguration findDiscoveryConfiguration(String modelFamily,
                                                               String benchmarkCode) {
        for (QuantResearchUniverse template : universeMapper.selectList(
                new LambdaQueryWrapper<QuantResearchUniverse>()
                        .eq(QuantResearchUniverse::getModelFamily, modelFamily)
                        .eq(QuantResearchUniverse::getActive, true)
                        .orderByAsc(QuantResearchUniverse::getId))) {
            Map<String, Object> root = readJson(template.getSelectionRuleJson());
            Map<String, Object> benchmarkRules = map(root.get("benchmarkRules"));
            Map<String, Object> rule = map(benchmarkRules.get(benchmarkCode));
            if (rule.isEmpty()) continue;
            rule = new LinkedHashMap<>(rule);
            rule.put("membershipMode", root.getOrDefault(
                    "membershipMode", "CURRENT_CATALOG_SNAPSHOT"));
            return new DiscoveryConfiguration(
                    rule,
                    positiveInt(rule.get("memberLimit"), "memberLimit"),
                    positiveInt(rule.get("minimumMembers"), "minimumMembers"),
                    positiveInt(rule.get("minimumRecords"), "minimumRecords")
            );
        }
        return null;
    }

    private InvestmentProduct upsertProduct(Map<String, Object> member) {
        String productType = text(member.getOrDefault("productType", "MUTUAL_FUND"));
        String market = text(member.getOrDefault("market", "FUND_CN"));
        String code = text(member.get("code"));
        InvestmentProduct product = productMapper.selectOne(
                new LambdaQueryWrapper<InvestmentProduct>()
                        .eq(InvestmentProduct::getProductType, productType)
                        .eq(InvestmentProduct::getMarket, market)
                        .eq(InvestmentProduct::getCode, code)
                        .last("LIMIT 1")
        );
        if (product == null) {
            product = new InvestmentProduct();
            product.setProductType(productType);
            product.setMarket(market);
            product.setCode(code);
            product.setCurrency("CNY");
        }
        product.setName(text(member.get("name")));
        product.setStatus("ACTIVE");
        if (product.getId() == null) productMapper.insert(product);
        else productMapper.updateById(product);
        return product;
    }

    private void upsertMembership(QuantResearchUniverse universe,
                                  InvestmentProduct product,
                                  InvestmentDataQualityService.Evaluation quality) {
        LocalDate validFrom = quality.snapshot().getSampleStartDate();
        QuantUniverseMembership membership = membershipMapper.selectOne(
                new LambdaQueryWrapper<QuantUniverseMembership>()
                        .eq(QuantUniverseMembership::getUniverseId, universe.getId())
                        .eq(QuantUniverseMembership::getCode, product.getCode())
                        .eq(QuantUniverseMembership::getValidFrom, validFrom)
                        .last("LIMIT 1")
        );
        if (membership == null) {
            membership = new QuantUniverseMembership();
            membership.setUniverseId(universe.getId());
            membership.setCode(product.getCode());
            membership.setValidFrom(validFrom);
            membership.setCreatedAt(LocalDateTime.now());
        }
        membership.setProductId(product.getId());
        membership.setProductType(product.getProductType());
        membership.setMarket(product.getMarket());
        membership.setValidTo(null);
        membership.setSourceSnapshot(quality.datasetVersion());
        if (membership.getId() == null) membershipMapper.insert(membership);
        else membershipMapper.updateById(membership);
    }

    private void persistQuotes(InvestmentProduct product, Map<String, Object> response) {
        Map<String, Object> manifest = map(response.get("manifest"));
        String provider = text(manifest.get("provider"));
        String adapterVersion = text(manifest.get("adapterVersion"));
        BigDecimal previousClose = null;
        List<Map<String, Object>> records = mapList(response.get("records")).stream()
                .sorted(Comparator.comparing(this::recordDate))
                .toList();
        for (Map<String, Object> record : records) {
            LocalDate tradeDate = recordDate(record);
            ProductDailyQuote quote = quoteMapper.selectOne(
                    new LambdaQueryWrapper<ProductDailyQuote>()
                            .eq(ProductDailyQuote::getProductId, product.getId())
                            .eq(ProductDailyQuote::getTradeDate, tradeDate)
                            .eq(ProductDailyQuote::getAdjustType, "NONE")
                            .last("LIMIT 1")
            );
            if (quote == null) {
                quote = new ProductDailyQuote();
                quote.setProductId(product.getId());
                quote.setTradeDate(tradeDate);
                quote.setAdjustType("NONE");
            }
            BigDecimal close = decimal(
                    record.get("close") == null ? record.get("nav") : record.get("close"));
            quote.setClosePrice(close);
            quote.setPreviousClose(previousClose);
            if (previousClose != null && previousClose.signum() != 0 && close != null) {
                BigDecimal change = close.subtract(previousClose);
                quote.setChangeAmount(change);
                quote.setChangePercent(change.divide(previousClose, 8, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")));
            }
            quote.setSource(provider);
            quote.setAdapterVersion(adapterVersion);
            quote.setSyncedAt(LocalDateTime.now());
            if (quote.getId() == null) quoteMapper.insert(quote);
            else quoteMapper.updateById(quote);
            previousClose = close;
        }
    }

    private List<ProductDailyQuote> quotes(Long productId) {
        return quoteMapper.selectList(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, productId)
                .orderByAsc(ProductDailyQuote::getTradeDate));
    }

    private InvestmentProduct requireProduct(Long productId) {
        InvestmentProduct product = productMapper.selectById(productId);
        if (product == null) throw new IllegalArgumentException("投资产品不存在");
        return product;
    }

    private String universeCode(String family, String benchmarkCode, String sourceVersion) {
        String hash = QuantExperimentFingerprint.canonicalHash(Map.of(
                "modelFamily", family,
                "benchmarkCode", benchmarkCode,
                "benchmarkSourceVersion", sourceVersion
        ));
        return "AUTO_" + family + "_" + hash.substring(0, 16);
    }

    private Map<String, Object> readJson(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("研究资产池筛选规则无法读取", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("研究资产池筛选规则无法保存", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static int positiveInt(Object value, String name) {
        int result;
        try {
            result = Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("研究资产池规则缺少 " + name, exception);
        }
        if (result < 1) throw new IllegalStateException("研究资产池规则 " + name + " 必须大于0");
        return result;
    }

    private LocalDate recordDate(Map<String, Object> record) {
        Object value = record.get("data_date");
        if (value == null) value = record.get("date");
        if (value == null) throw new IllegalStateException("研究资产行情缺少日期");
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    private static BigDecimal decimal(Object value) {
        return value == null || "null".equals(String.valueOf(value))
                ? null : new BigDecimal(String.valueOf(value));
    }

    private static String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("研究资产返回缺少必要字段");
        }
        return String.valueOf(value);
    }

    private record DiscoveryConfiguration(Map<String, Object> rule,
                                          int memberLimit,
                                          int minimumMembers,
                                          int minimumRecords) { }
}
