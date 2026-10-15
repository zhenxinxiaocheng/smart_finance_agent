package com.smartfinance.agent.investment.marketdata.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HexFormat;
import java.security.MessageDigest;
import org.springframework.core.io.ClassPathResource;

/** The same storage contract is packaged for Java and read by Python. */
public record MarketDataContract(String version, List<String> requiredFields) {
    private static final JsonNode ROOT = load();
    private static final JsonNode DEFINITIONS = ROOT.get("contracts");
    private static final JsonNode PRICE_NORMALIZATION=load("technical-strategy-v5.json").get("providers");
    private static final JsonNode RESEARCH_NORMALIZATION=load("research-data-v1.json");

    private static JsonNode load() {
        return load("data-contracts-v1.json");
    }
    private static JsonNode load(String name) {
        try (var stream = new ClassPathResource(name).getInputStream()) {
            return new ObjectMapper().readTree(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("数据字段规则无法读取", failure);
        }
    }

    public static MarketDataContract forProduct(String dataset, String productType) {
        for (var row : DEFINITIONS) {
            if (contains(row.get("datasets"), dataset) && contains(row.get("productTypes"), productType)) {
                var required = new ArrayList<String>();
                row.get("requiredFields").forEach(item -> required.add(item.asText()));
                var normalization=new TreeMap<String,Object>();
                JsonNode source="PRICE".equals(dataset)?PRICE_NORMALIZATION:RESEARCH_NORMALIZATION;
                List<String> keys="PRICE".equals(dataset)?List.of("history_adapter_version","history_units","tencent_volume_lot_size","tencent_amount_unit")
                        :List.of("version","fieldAliases","fieldUnits","dateColumns","publicationColumns","identityColumns");
                for(String key:keys)normalization.put(key,canonical(source.get(key)));
                try {
                    var payload=new TreeMap<String,Object>();payload.put("contract",canonical(row));payload.put("normalization",normalization);
                    String version=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsBytes(payload)));
                    return new MarketDataContract(version, List.copyOf(required));
                } catch(Exception failure) { throw new IllegalStateException("数据规则版本计算失败",failure); }
            }
        }
        throw new IllegalArgumentException("不支持的数据规则：" + dataset + "/" + productType);
    }

    private static boolean contains(JsonNode values, String value) {
        for (var item : values) if (item.asText().equals(value)) return true;
        return false;
    }
    private static Object canonical(JsonNode node) {
        if(node.isObject()) {
            var sorted=new TreeMap<String,Object>();node.fields().forEachRemaining(entry->sorted.put(entry.getKey(),canonical(entry.getValue())));return sorted;
        }
        if(node.isArray()) { var items=new ArrayList<Object>();node.forEach(value->items.add(canonical(value)));return items; }
        if(node.isIntegralNumber())return node.longValue();
        if(node.isBoolean())return node.booleanValue();
        if(node.isNull())return null;
        return node.asText();
    }

    public static String researchAdjustment(String productType,String market) {
        for(var rule:ROOT.get("adjustmentPolicies"))
            if(contains(rule.get("productTypes"),productType)&&contains(rule.get("markets"),market))return rule.get("researchAdjustment").asText();
        return ROOT.get("defaultResearchAdjustment").asText();
    }
    public List<String> quoteColumns() {
        Map<String,String> columns=Map.ofEntries(Map.entry("data_date","trade_date"),Map.entry("open","open_price"),
                Map.entry("high","high_price"),Map.entry("low","low_price"),Map.entry("close","close_price"),
                Map.entry("volume","volume"),Map.entry("amount","amount"),Map.entry("previous_close","previous_close"),
                Map.entry("turnover_rate","turnover_rate"),Map.entry("total_return_index","total_return_index"),
                Map.entry("amplitude","amplitude"),Map.entry("volume_ratio","volume_ratio"));
        var required=new ArrayList<String>();
        for(String field:requiredFields) {
            if(!columns.containsKey(field))throw new IllegalStateException("数据库字段尚未支持数据规则："+field);
            required.add(columns.get(field));
        }
        return List.copyOf(required);
    }

    public void validate(Map<String, Object> response, List<Map<String, Object>> records) {
        Object declared = response.get("dataContractVersion");
        if (declared == null && response.get("manifest") instanceof Map<?, ?> manifest)
            declared = manifest.get("dataContractVersion");
        if (!version.equals(declared)) throw new IllegalStateException("数据规则版本不一致，需要按当前规则重新采集");
        for (var row : records) for (String field : requiredFields)
            if (row.get(field) == null || "".equals(row.get(field)))
                throw new IllegalStateException("数据源缺少必需字段：" + field);
    }
}
