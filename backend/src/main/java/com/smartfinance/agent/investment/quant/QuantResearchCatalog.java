package com.smartfinance.agent.investment.quant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class QuantResearchCatalog {
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private QuantResearchCatalog() {
    }

    static List<Map<String, Object>> modelFamilies() {
        return List.of(
                family(
                        "A_SHARE_STOCK",
                        "A股股票",
                        List.of("RELATIVE_ALPHA", "ABSOLUTE_RETURN", "DOWNSIDE_PROBABILITY")
                ),
                family(
                        "INDEX_FUND",
                        "指数及指数增强基金",
                        List.of("BENCHMARK_ALLOCATION", "TRACKING_ALPHA")
                ),
                family(
                        "ACTIVE_FUND",
                        "主动基金",
                        List.of("MARKET_ALLOCATION", "PEER_ALPHA")
                ),
                family(
                        "QDII_INDEX_FUND",
                        "QDII指数基金",
                        List.of("FX_ADJUSTED_ALLOCATION", "TRACKING_ALPHA")
                ),
                family(
                        "COMMODITY_FUND",
                        "商品基金",
                        List.of("COMMODITY_ALLOCATION", "TRACKING_ALPHA")
                )
        );
    }

    static Map<String, Object> parameterSchema() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "quant-parameter-schema-v1");
        result.put("validationMode", "STRICT");
        result.put(
                "algorithms",
                List.of("ELASTIC_NET", "GRADIENT_BOOSTING", "VALIDATED_ENSEMBLE")
        );
        result.put("horizons", List.of("SHORT", "MEDIUM", "LONG"));
        result.put("fields", List.of(
                field("linearWeight", "集成线性权重", "NUMBER", 0.5, 0.0, 1.0, 0.05),
                field("classificationC", "Logistic正则强度", "NUMBER", 0.5, 0.01, 10.0, 0.01),
                field("regressionAlpha", "ElasticNet Alpha", "NUMBER", 0.001, 0.00001, 1.0, 0.00001),
                field("estimators", "提升树数量", "INTEGER", 48, 16, 500, 1),
                field("maximumDepth", "提升树最大深度", "INTEGER", 3, 1, 8, 1),
                field("learningRate", "提升树学习率", "NUMBER", 0.05, 0.005, 0.3, 0.005)
        ));
        result.put("immutableValidation", Map.of(
                "minimumWalkForwardFolds", 5,
                "embargoHorizonMultiplier", 1.0,
                "maximumDmPValue", 0.05,
                "minimumDeflatedSharpeProbability", 0.95,
                "maximumPbo", 0.20
        ));
        return result;
    }

    static String experimentFingerprint(
            Long userId,
            Long assetId,
            String modelFamily,
            String horizonCode,
            Map<String, Object> parameters
    ) {
        return experimentFingerprint(
                userId,
                assetId,
                modelFamily,
                horizonCode,
                "VALIDATED_ENSEMBLE",
                parameters
        );
    }

    static String experimentFingerprint(
            Long userId,
            Long assetId,
            String modelFamily,
            String horizonCode,
            String algorithm,
            Map<String, Object> parameters
    ) {
        return experimentFingerprint(
                userId, assetId, null, null, modelFamily, horizonCode, algorithm, parameters);
    }

    static String experimentFingerprint(
            Long userId,
            Long assetId,
            Long universeId,
            String universeDatasetVersion,
            String modelFamily,
            String horizonCode,
            String algorithm,
            Map<String, Object> parameters
    ) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("userId", userId);
        material.put("assetId", assetId);
        material.put("universeId", universeId);
        material.put("universeDatasetVersion", universeDatasetVersion);
        material.put("modelFamily", modelFamily);
        material.put("horizonCode", horizonCode);
        material.put("algorithm", algorithm);
        material.put("parameters", parameters == null ? Map.of() : parameters);
        return canonicalHash(material);
    }

    static String canonicalHash(Object material) {
        try {
            byte[] payload = CANONICAL_MAPPER.writeValueAsBytes(material);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成量化实验指纹", exception);
        }
    }

    private static Map<String, Object> family(
            String code,
            String name,
            List<String> predictionHeads
    ) {
        return Map.of(
                "code", code,
                "name", name,
                "predictionHeads", predictionHeads
        );
    }

    private static Map<String, Object> field(
            String key,
            String label,
            String type,
            Number defaultValue,
            Number minimum,
            Number maximum,
            Number step
    ) {
        return Map.of(
                "key", key,
                "label", label,
                "type", type,
                "defaultValue", defaultValue,
                "minimum", minimum,
                "maximum", maximum,
                "step", step
        );
    }
}
