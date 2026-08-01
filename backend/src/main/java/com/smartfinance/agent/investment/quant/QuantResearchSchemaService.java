package com.smartfinance.agent.investment.quant;

import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class QuantResearchSchemaService {
    private final AnalysisServiceClient analysisServiceClient;

    public QuantResearchSchemaService(AnalysisServiceClient analysisServiceClient) {
        this.analysisServiceClient = analysisServiceClient;
    }

    public SchemaSnapshot load() {
        Map<String, Object> manifest = analysisServiceClient.quantRuntimeManifest();
        String quantConfigVersion = requiredText(
                manifest.get("quantConfigVersion"),
                "量化配置版本缺失"
        );
        Map<String, Object> schema = requiredMap(
                manifest.get("researchSchema"),
                "分析服务未提供量化研究参数定义"
        );
        List<Map<String, Object>> families = mapList(
                schema.get("modelFamilies"),
                "模型家族定义缺失"
        );
        List<String> algorithms = stringList(
                schema.get("algorithms"),
                "算法定义缺失"
        );
        List<Map<String, Object>> fields = mapList(
                schema.get("fields"),
                "参数定义缺失"
        );
        Map<String, Object> validation = requiredMap(
                schema.get("immutableValidation"),
                "验证门槛定义缺失"
        );
        if (families.isEmpty() || algorithms.isEmpty() || fields.isEmpty()) {
            throw new IllegalStateException("量化研究参数定义不完整");
        }

        Map<String, Object> parameterSchema = new LinkedHashMap<>();
        parameterSchema.put("schemaVersion", requiredText(
                schema.get("schemaVersion"),
                "研究参数版本缺失"
        ));
        parameterSchema.put("quantConfigVersion", quantConfigVersion);
        parameterSchema.put("validationMode", "STRICT");
        parameterSchema.put("modelFamilies", families);
        parameterSchema.put("algorithms", algorithms);
        parameterSchema.put("fields", fields);
        parameterSchema.put("immutableValidation", validation);
        return new SchemaSnapshot(
                quantConfigVersion,
                List.copyOf(families),
                Map.copyOf(parameterSchema),
                Set.copyOf(algorithms),
                fieldsByKey(fields)
        );
    }

    public record SchemaSnapshot(
            String quantConfigVersion,
            List<Map<String, Object>> modelFamilies,
            Map<String, Object> parameterSchema,
            Set<String> algorithms,
            Map<String, Map<String, Object>> fields
    ) {
        public String normalizeFamily(String value) {
            String normalized = normalize(value, "请选择模型家族");
            boolean supported = modelFamilies.stream().anyMatch(
                    item -> normalized.equals(String.valueOf(item.get("code")))
            );
            if (!supported) {
                throw new IllegalArgumentException("不支持的模型家族");
            }
            return normalized;
        }

        public String normalizeAlgorithm(String value) {
            String normalized = normalize(value, "请选择量化算法");
            if (!algorithms.contains(normalized)) {
                throw new IllegalArgumentException("不支持的量化算法");
            }
            return normalized;
        }

        public void validateParameters(
                Map<String, Object> parameters,
                String algorithm
        ) {
            if (parameters == null) {
                throw new IllegalArgumentException("模型参数不能为空");
            }
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                Map<String, Object> field = fields.get(entry.getKey());
                if (field == null) {
                    throw new IllegalArgumentException(
                            "不支持的模型参数: " + entry.getKey()
                    );
                }
                Object rawAlgorithms = field.get("algorithms");
                if (rawAlgorithms instanceof List<?> supported
                        && supported.stream().noneMatch(algorithm::equals)) {
                    throw new IllegalArgumentException(
                            "参数不适用于当前算法: " + entry.getKey()
                    );
                }
                if (!(entry.getValue() instanceof Number number)) {
                    throw new IllegalArgumentException(
                            "模型参数必须是数字: " + entry.getKey()
                    );
                }
                double value = number.doubleValue();
                double minimum = number(field, "minimum").doubleValue();
                double maximum = number(field, "maximum").doubleValue();
                if (!Double.isFinite(value) || value < minimum || value > maximum) {
                    throw new IllegalArgumentException(
                            "模型参数超出允许范围: " + entry.getKey()
                    );
                }
                if ("INTEGER".equals(field.get("type")) && value != Math.rint(value)) {
                    throw new IllegalArgumentException(
                            "模型参数必须是整数: " + entry.getKey()
                    );
                }
            }
        }

        private static String normalize(String value, String missingMessage) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(missingMessage);
            }
            return value.trim().toUpperCase(Locale.ROOT);
        }

        private static Number number(Map<String, Object> field, String key) {
            Object value = field.get(key);
            if (!(value instanceof Number number)) {
                throw new IllegalStateException("量化参数范围定义无效: " + field.get("key"));
            }
            return number;
        }
    }

    private static Map<String, Map<String, Object>> fieldsByKey(
            List<Map<String, Object>> fields
    ) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String key = requiredText(field.get("key"), "量化参数名称缺失");
            if (result.putIfAbsent(key, field) != null) {
                throw new IllegalStateException("量化参数名称重复: " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static List<Map<String, Object>> mapList(Object value, String message) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(message);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(requiredMap(item, message));
        }
        return result;
    }

    private static List<String> stringList(Object value, String message) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(message);
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            result.add(requiredText(item, message).toUpperCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> requiredMap(Object value, String message) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalStateException(message);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Map.copyOf(result);
    }

    private static String requiredText(Object value, String message) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException(message);
        }
        return String.valueOf(value).trim();
    }
}
