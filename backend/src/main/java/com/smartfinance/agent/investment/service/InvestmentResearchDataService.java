package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Immutable observations for research; analysis snapshots remain presentation results. */
@Service
public class InvestmentResearchDataService {
    private final JdbcTemplate db;
    private final AnalysisServiceClient analysis;
    private final ObjectMapper json;
    private volatile List<Map<String, Object>> definitions = List.of();

    public InvestmentResearchDataService(JdbcTemplate db, AnalysisServiceClient analysis, ObjectMapper json) {
        this.db = db;
        this.analysis = analysis;
        this.json = json;
    }

    public void refreshCapabilities() {
        List<Map<String, Object>> loaded = analysis.researchCapabilities();
        if (loaded == null || loaded.isEmpty()) throw new IllegalStateException("研究数据集为空");
        for (Map<String, Object> item : loaded) {
            String dataset = String.valueOf(item.get("dataset"));
            if (!dataset.matches("[A-Z_]{1,20}") || !(item.get("families") instanceof List<?>)
                    || !(item.get("refreshDays") instanceof Number n) || n.intValue() < 1)
                throw new IllegalStateException("研究数据集配置无效");
        }
        definitions = List.copyOf(loaded);
    }

    public List<Map<String, Object>> datasets(InvestmentProduct product) {
        String family = family(product);
        return definitions.stream().filter(row -> ((List<?>) row.get("families")).contains(family)).toList();
    }

    public boolean supports(String dataset) {
        return definitions.stream().anyMatch(row -> dataset.equals(row.get("dataset")));
    }

    public boolean yearly(String dataset) {
        return definitions.stream().anyMatch(row -> dataset.equals(row.get("dataset")) && Boolean.TRUE.equals(row.get("yearly")));
    }

    public Map<String, Object> collect(InvestmentProduct product, String dataset, LocalDate start, LocalDate end) {
        return analysis.researchData(product, dataset, start, end);
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public int persist(long productId, String dataset, Map<String, Object> response) {
        if (response == null || !dataset.equals(response.get("dataset")) || !(response.get("records") instanceof List<?> records))
            throw new IllegalStateException("研究数据响应无效");
        if (db.update("UPDATE investment_product SET id=id WHERE id=?", productId) != 1)
            throw new IllegalStateException("研究产品不存在");
        int inserted = 0;
        for (Object value : records) {
            if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("研究记录无效");
            Map<String, Object> row = (Map<String, Object>) value;
            String key = hash(row.get("recordKey")), content = hash(row.get("contentHash"));
            String provider = Objects.toString(row.get("provider"), "");
            if (provider.isBlank() || provider.length() > 64 || !(row.get("payload") instanceof Map<?, ?> payload))
                throw new IllegalStateException("研究记录来源或内容无效");
            LocalDateTime observed = OffsetDateTime.parse(String.valueOf(row.get("observedAt")))
                    .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            LocalDate businessDate = date(row.get("asOfDate")), published = date(row.get("publishedDate"));
            List<Map<String, Object>> previous = db.queryForList("SELECT content_hash FROM investment_research_record "
                    + "WHERE product_id=? AND dataset=? AND provider=? AND record_key=? ORDER BY id DESC LIMIT 1",
                    productId, dataset, provider, key);
            if (!previous.isEmpty() && content.equals(previous.get(0).get("content_hash"))) continue;
            // Only explicitly versioned filings can establish a historical publication
            // date. A changed response under the same identity becomes knowable now.
            boolean filed = previous.isEmpty() && "FILED_DATE".equals(row.get("availabilityBasis")) && published != null;
            LocalDateTime available = filed ? published.plusDays(1)
                    .atStartOfDay(ZoneId.of(String.valueOf(row.get("publicationTimezone"))))
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime() : observed;
            // 日期级发布时间按发布地次日才开放读取，允许今天采集的记录明天可用。
            if (filed && published.isAfter(observed.atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(ZoneId.of(String.valueOf(row.get("publicationTimezone")))).toLocalDate()))
                throw new IllegalStateException("研究记录的发布日期在采集时间之后");
            inserted += db.update("INSERT INTO investment_research_record(product_id,dataset,record_key,content_hash,"
                            + "provider,adapter_version,as_of_date,published_date,observed_at,available_at,availability_basis,payload_json) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    productId, dataset, key, content, provider, String.valueOf(response.get("adapterVersion")),
                    businessDate, published, observed, available, filed ? "FILED_DATE" : "FIRST_OBSERVED", encode(payload));
            if ("PROFILE".equals(dataset)) {
                Map<?, ?> values = payload.get("values") instanceof Map<?, ?> map ? map : Map.of();
                for (String name : List.of("inceptionDate", "listingDate")) {
                    if (values.get(name) instanceof Map<?, ?> field) {
                        String text = Objects.toString(field.get("value"), "");
                        if (text.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                            String column = "inceptionDate".equals(name) ? "inception_date" : "listing_date";
                            db.update("UPDATE investment_product SET " + column + "=COALESCE(" + column + ",?) WHERE id=?",
                                    LocalDate.parse(text.substring(0, 10)), productId);
                        }
                    }
                }
            }
        }
        return inserted;
    }

    public Map<String, Object> read(long productId, String dataset, OffsetDateTime asOf, int page, int size) {
        if (!dataset.matches("[A-Z_]{1,20}") || page < 1 || size < 1 || size > 1000)
            throw new IllegalArgumentException("研究读取参数无效");
        LocalDateTime cutoff = asOf.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        String where = " FROM investment_research_record r WHERE r.product_id=? AND r.dataset=? AND r.available_at<=? "
                + "AND NOT EXISTS(SELECT 1 FROM investment_research_record newer WHERE newer.product_id=r.product_id "
                + "AND newer.dataset=r.dataset AND newer.provider=r.provider AND newer.record_key=r.record_key "
                + "AND newer.available_at<=? AND newer.id>r.id)";
        Long count = db.queryForObject("SELECT COUNT(*)" + where, Long.class, productId, dataset, cutoff, cutoff);
        List<Map<String, Object>> items = db.queryForList("SELECT r.id,r.product_id,r.dataset,r.record_key,r.content_hash,"
                        + "r.provider,r.adapter_version,r.as_of_date,r.published_date,r.availability_basis,r.payload_json,"
                        + "CAST(r.observed_at AS CHAR) AS observed_at,CAST(r.available_at AS CHAR) AS available_at" + where
                        + " ORDER BY r.as_of_date,r.id LIMIT ? OFFSET ?", productId, dataset, cutoff, cutoff,
                size, Math.multiplyExact((long) page - 1, size));
        for (Map<String, Object> item : items) {
            item.put("payload", decode(String.valueOf(item.remove("payload_json"))));
            for (String name : List.of("observed_at", "available_at"))
                item.put(name, LocalDateTime.parse(String.valueOf(item.get(name)).replace(' ', 'T')).atOffset(ZoneOffset.UTC).toString());
        }
        return Map.of("productId", productId, "dataset", dataset, "asOf", asOf.toString(),
                "page", page, "size", size, "total", count == null ? 0 : count, "items", items);
    }

    public List<Map<String, Object>> overview() {
        return db.queryForList("SELECT p.catalog_market,r.dataset,COUNT(*) AS record_count,"
                + "COUNT(DISTINCT r.product_id) AS product_count,MIN(r.as_of_date) AS first_date,"
                + "MAX(r.as_of_date) AS last_date,MAX(r.observed_at) AS last_observed,"
                + "SUM(CASE WHEN r.availability_basis='FILED_DATE' THEN 1 ELSE 0 END) AS historical_publications "
                + "FROM investment_research_record r JOIN investment_product p ON p.id=r.product_id "
                + "GROUP BY p.catalog_market,r.dataset ORDER BY p.catalog_market,r.dataset");
    }

    public List<Map<String, Object>> capabilities() { return definitions; }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("研究资料序列化失败", error); }
    }

    private Object decode(String value) {
        try { return json.readValue(value, Object.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("研究资料读取失败", error); }
    }

    private static String hash(Object value) {
        String result = Objects.toString(value, "");
        if (!result.matches("[0-9a-f]{64}")) throw new IllegalStateException("研究记录缺少有效版本标识");
        return result;
    }

    private static LocalDate date(Object value) {
        return value == null ? null : LocalDate.parse(String.valueOf(value));
    }

    private static String family(InvestmentProduct product) {
        if ("STOCK".equals(product.getProductType()) && Set.of("SSE", "SZSE", "BSE").contains(product.getMarket())) return "CN_STOCK";
        if ("FUND_CN".equals(product.getMarket()) || "ETF".equals(product.getProductType())
                && Set.of("SSE", "SZSE", "BSE").contains(product.getMarket())) return "CN_FUND";
        return Set.of("NASDAQ", "NYSE", "AMEX").contains(product.getMarket()) ? "US_" + product.getProductType() : "";
    }
}
