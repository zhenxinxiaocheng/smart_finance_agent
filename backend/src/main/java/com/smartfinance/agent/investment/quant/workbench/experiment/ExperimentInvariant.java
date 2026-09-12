package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class ExperimentInvariant {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private ExperimentInvariant() {}
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value) {
        return value instanceof Map<?,?> ? new LinkedHashMap<>((Map<String,Object>)value) : new LinkedHashMap<>();
    }
    public static String encode(Object value) { try { return JSON.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException(e); } }
    public static Map<String,Object> decode(Object value) { try { return value == null ? new LinkedHashMap<>() : map(JSON.readValue(value.toString(), Object.class)); } catch(Exception e) { throw new IllegalStateException("实验记录损坏",e); } }
    public static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static boolean same(Object left, Object right) {
        return JSON.valueToTree(left).equals(JSON.valueToTree(right)) || encode(normalize(left)).equals(encode(normalize(right)));
    }
    private static Object normalize(Object value) {
        if(value instanceof Number n) return new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        if(value instanceof Map<?,?> map) { var out=new TreeMap<String,Object>(); map.forEach((k,v)->out.put(k.toString(),normalize(v))); return out; }
        if(value instanceof List<?> list) return list.stream().map(ExperimentInvariant::normalize).toList();
        return value;
    }
    public static String calculate(Map<String,Object> base, Map<String,Object> environment, String snapshotHash, String key, Object contract, Object assumptions) {
        var input = map(base); var config = map(input.get("config")); config.remove(key); input.put("config",config);
        input.remove("assets"); input.remove("expectedRuntime");
        return hash(Map.of("request", input, "environment", environment, "snapshotHash", snapshotHash, "benchmark", contract, "assumptions", assumptions));
    }
    public static Map<String,Object> benchmark(Object value) {
        var result = map(value); result.keySet().removeAll(Set.of("metrics","equityCurve","excessReturn","benchmarkReturn")); return result;
    }
    public static void require(boolean condition, String code) { if (!condition) throw new ExperimentException(code); }
    public static final class ExperimentException extends RuntimeException {
        private final String code;
        public ExperimentException(String code) { super(code); this.code=code; }
        public String code() { return code; }
    }
}
