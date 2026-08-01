package com.smartfinance.agent.investment.quant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

final class QuantExperimentFingerprint {
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private QuantExperimentFingerprint() {
    }

    static String experiment(
            Long userId,
            Long assetId,
            String modelFamily,
            String horizonCode,
            Map<String, Object> parameters
    ) {
        return experiment(
                userId, assetId, modelFamily, horizonCode,
                "REGIME_ENSEMBLE", parameters
        );
    }

    static String experiment(
            Long userId,
            Long assetId,
            String modelFamily,
            String horizonCode,
            String algorithm,
            Map<String, Object> parameters
    ) {
        return experiment(
                userId, assetId, null, null, modelFamily, horizonCode,
                algorithm, parameters
        );
    }

    static String experiment(
            Long userId,
            Long assetId,
            Long universeId,
            String universeDatasetVersion,
            String modelFamily,
            String horizonCode,
            String algorithm,
            Map<String, Object> parameters
    ) {
        Map<String, Object> material = baseMaterial(
                userId, assetId, universeId, universeDatasetVersion,
                modelFamily, horizonCode, algorithm, parameters
        );
        return canonicalHash(material);
    }

    static String experiment(
            Long userId,
            Long assetId,
            Long universeId,
            String universeDatasetVersion,
            String modelFamily,
            String horizonCode,
            String horizonProfileVersion,
            int horizonDays,
            String algorithm,
            Map<String, Object> parameters
    ) {
        Map<String, Object> material = baseMaterial(
                userId, assetId, universeId, universeDatasetVersion,
                modelFamily, horizonCode, algorithm, parameters
        );
        material.put("horizonProfileVersion", horizonProfileVersion);
        material.put("horizonDays", horizonDays);
        return canonicalHash(material);
    }

    static String canonicalHash(Object material) {
        try {
            byte[] payload = CANONICAL_MAPPER.writeValueAsBytes(material);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload)
            );
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成量化实验指纹", exception);
        }
    }

    private static Map<String, Object> baseMaterial(
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
        return material;
    }
}
