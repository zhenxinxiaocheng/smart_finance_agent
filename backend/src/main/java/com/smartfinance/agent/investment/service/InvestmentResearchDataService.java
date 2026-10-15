package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.marketdata.contract.MarketDataContract;
import com.smartfinance.agent.investment.marketdata.collection.MarketDataCollectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final MarketDataCollectionRepository collections;
    private volatile List<Map<String, Object>> definitions = List.of();

    @Autowired
    public InvestmentResearchDataService(JdbcTemplate db, AnalysisServiceClient analysis, ObjectMapper json,
                                         MarketDataCollectionRepository collections) {
        this.db = db;
        this.analysis = analysis;
        this.json = json;
        this.collections = collections;
    }

    InvestmentResearchDataService(JdbcTemplate db, AnalysisServiceClient analysis, ObjectMapper json) {
        this(db,analysis,json,new MarketDataCollectionRepository(db));
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
        var response=analysis.researchData(product, dataset, start, end);
        validateContract(product.getProductType(),dataset,response);
        return response;
    }

    @SuppressWarnings("unchecked")
    private void validateContract(String type,String dataset,Map<String,Object> response) {
        if(response==null||!(response.get("records") instanceof List<?>))throw new IllegalStateException("研究数据响应无效");
        MarketDataContract.forProduct(dataset,type).validate(response,(List<Map<String,Object>>)response.get("records"));
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public int persist(long productId, String dataset, Map<String, Object> response) {
        if (response == null || !dataset.equals(response.get("dataset")) || !(response.get("records") instanceof List<?> records))
            throw new IllegalStateException("研究数据响应无效");
        if (db.update("UPDATE investment_product SET id=id WHERE id=?", productId) != 1)
            throw new IllegalStateException("研究产品不存在");
        String contract=Objects.toString(response.get("dataContractVersion"),null);
        if(contract!=null)validateContract(db.queryForObject("SELECT product_type FROM investment_product WHERE id=?",String.class,productId),dataset,response);
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
            String sourceHash=row.get("sourceContentHash")==null?sourceHash(payload):hash(row.get("sourceContentHash"));
            List<Map<String, Object>> previous = db.queryForList("SELECT content_hash,data_contract_version,source_content_hash,payload_json,available_at,observed_at,first_observed_at,availability_basis FROM investment_research_record "
                    + "WHERE product_id=? AND dataset=? AND provider=? AND record_key=? ORDER BY id DESC LIMIT 1",
                    productId, dataset, provider, key);
            if (!previous.isEmpty() && content.equals(previous.get(0).get("content_hash"))
                    && Objects.equals(contract,previous.get(0).get("data_contract_version"))) continue;
            // Only explicitly versioned filings can establish a historical publication
            // date. A changed response under the same identity becomes knowable now.
            boolean filed = previous.isEmpty() && "FILED_DATE".equals(row.get("availabilityBasis")) && published != null;
            LocalDateTime available = filed ? published.plusDays(1)
                    .atStartOfDay(ZoneId.of(String.valueOf(row.get("publicationTimezone"))))
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime() : observed;
            String basis=filed?"FILED_DATE":"FIRST_OBSERVED";
            LocalDateTime firstObserved=observed;
            if(!previous.isEmpty()) {
                var old=previous.get(0);
                String oldHash=Objects.toString(old.get("source_content_hash"),null);
                boolean sameFacts=oldHash!=null ? sourceHash.equals(oldHash)
                        : decode(old.get("payload_json").toString()) instanceof Map<?,?> oldPayload && sameSourceFacts(oldPayload,payload);
                if(sameFacts) {
                    available=LocalDateTime.parse(old.get("available_at").toString().replace(' ','T'));
                    firstObserved=LocalDateTime.parse(Objects.toString(old.get("first_observed_at"),old.get("observed_at").toString()).replace(' ','T'));
                    basis=old.get("availability_basis").toString();
                }
            }
            // 日期级发布时间按发布地次日才开放读取，允许今天采集的记录明天可用。
            if (filed && published.isAfter(observed.atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(ZoneId.of(String.valueOf(row.get("publicationTimezone")))).toLocalDate()))
                throw new IllegalStateException("研究记录的发布日期在采集时间之后");
            inserted += db.update("INSERT INTO investment_research_record(product_id,dataset,record_key,content_hash,"
                            + "provider,adapter_version,as_of_date,published_date,observed_at,available_at,availability_basis,payload_json,data_contract_version,source_content_hash,first_observed_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    productId, dataset, key, content, provider, String.valueOf(response.get("adapterVersion")),
                    businessDate, published, observed, available, basis, encode(payload),contract,sourceHash,firstObserved);
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
        String contract=collections.currentVersion(productId,dataset);
        String where = " FROM investment_research_record r WHERE r.product_id=? AND r.dataset=? AND r.data_contract_version=? AND r.available_at<=? "
                + "AND NOT EXISTS(SELECT 1 FROM investment_research_record newer WHERE newer.product_id=r.product_id "
                + "AND newer.dataset=r.dataset AND newer.provider=r.provider AND newer.record_key=r.record_key "
                + "AND newer.data_contract_version=r.data_contract_version AND newer.available_at<=? AND newer.id>r.id)";
        Long count = db.queryForObject("SELECT COUNT(*)" + where, Long.class, productId, dataset, contract,cutoff, cutoff);
        List<Map<String, Object>> items = db.queryForList("SELECT r.id,r.product_id,r.dataset,r.record_key,r.content_hash,"
                        + "r.provider,r.adapter_version,r.as_of_date,r.published_date,r.availability_basis,r.payload_json,"
                        + "CAST(r.observed_at AS CHAR) AS observed_at,CAST(r.available_at AS CHAR) AS available_at" + where
                        + " ORDER BY r.as_of_date,r.id LIMIT ? OFFSET ?", productId, dataset, contract,cutoff, cutoff,
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

    private String sourceHash(Map<?,?> payload) {
        return collections.hash(Map.of("section",Objects.toString(payload.get("section"),""),"raw",payload.get("raw")==null?Map.of():payload.get("raw")));
    }

    private boolean sameSourceFacts(Map<?,?> left,Map<?,?> right) {
        return Objects.toString(left.get("section"),"").equals(Objects.toString(right.get("section"),""))
                &&sameJsonValue(json.valueToTree(left.get("raw")),json.valueToTree(right.get("raw")));
    }
    private static boolean sameJsonValue(com.fasterxml.jackson.databind.JsonNode left,com.fasterxml.jackson.databind.JsonNode right) {
        if(left.isNumber()&&right.isNumber())return left.decimalValue().compareTo(right.decimalValue())==0;
        if(left.isObject()&&right.isObject()) {
            if(left.size()!=right.size())return false;
            var names=left.fieldNames();
            while(names.hasNext()) {
                String name=names.next();
                if(!right.has(name)||!sameJsonValue(left.get(name),right.get(name)))return false;
            }
            return true;
        }
        if(left.isArray()&&right.isArray()) {
            if(left.size()!=right.size())return false;
            for(int i=0;i<left.size();i++)if(!sameJsonValue(left.get(i),right.get(i)))return false;
            return true;
        }
        return left.equals(right);
    }

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
