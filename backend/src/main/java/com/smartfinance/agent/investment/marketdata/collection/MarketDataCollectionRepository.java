package com.smartfinance.agent.investment.marketdata.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.smartfinance.agent.investment.marketdata.contract.MarketDataContract;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared source responses; this repository owns neither demand nor job scheduling. */
@Repository
public class MarketDataCollectionRepository {
    public record Window(LocalDate start, LocalDate end) { }
    private final JdbcTemplate db;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    @Value("${market-data.empty-result-ttl:1h}") private Duration emptyTtl=Duration.ofHours(1);

    @Autowired public MarketDataCollectionRepository(JdbcTemplate db) { this(db,Clock.systemUTC()); }
    public MarketDataCollectionRepository(JdbcTemplate db,Clock clock) { this.db=db;this.clock=clock; }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void verifySchema() {
        var columns=new TreeSet<String>();columns.add("data_contract_version");
        for(String type:List.of("STOCK","ETF","INDEX","MUTUAL_FUND"))columns.addAll(MarketDataContract.forProduct("PRICE",type).quoteColumns());
        db.queryForList("SELECT "+String.join(",",columns)+" FROM product_daily_quote WHERE 1=0");
        db.queryForList("SELECT data_contract_version,source_content_hash,first_observed_at FROM investment_research_record WHERE 1=0");
        db.queryForList("SELECT product_id,dataset,data_contract_version,source_result FROM market_data_collection_batch WHERE 1=0");
    }
    public String currentVersion(long product,String dataset) { return contract(product,dataset).version(); }
    private MarketDataContract contract(long product,String dataset) {
        String type=db.queryForObject("SELECT product_type FROM investment_product WHERE id=?",String.class,product);
        return MarketDataContract.forProduct(dataset,type);
    }

    @SuppressWarnings("unchecked")
    public void record(long product,String dataset,String adjustment,LocalDate start,LocalDate end,Map<String,Object> response) {
        if(start==null||end==null||start.isAfter(end))throw new IllegalArgumentException("采集区间无效");
        if(Boolean.FALSE.equals(response.get("complete")))throw new IllegalStateException("源端分区未完成");
        if(!(response.get("records") instanceof List<?> items))throw new IllegalStateException("源端记录格式无效");
        var records=new ArrayList<Map<String,Object>>();
        for(Object item:items) {
            if(!(item instanceof Map<?,?>))throw new IllegalStateException("源端记录格式无效");
            records.add((Map<String,Object>)item);
        }
        var policy=contract(product,dataset);
        policy.validate(response,records);
        var dates=new TreeSet<LocalDate>();
        if("PRICE".equals(dataset))for(var row:records) {
            LocalDate day=LocalDate.parse(row.get("data_date").toString().substring(0,10));
            if(day.isBefore(start)||day.isAfter(end)||!dates.add(day))throw new IllegalStateException("源端日期越界或重复");
        }
        String result=records.isEmpty()?"EMPTY":"DATA";
        if("CLOSED".equals(response.get("result"))) {
            if(!records.isEmpty()||!Boolean.TRUE.equals(response.get("calendarVerified")))throw new IllegalStateException("休市记录缺少日历依据");
            result="CLOSED";
        }
        Map<?,?> manifest=response.get("manifest") instanceof Map<?,?> map?map:response;
        String content=hash(Map.of("records",records,"result",result));
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        LocalDateTime valid="DATA".equals(result)?null:now.plus(emptyTtl);
        Object[] key={product,dataset,adjustment,policy.version(),start,end,content};
        String where="product_id=? AND dataset=? AND adjust_type=? AND data_contract_version=? AND requested_start_date=? AND requested_end_date=? AND content_hash=?";
        if(db.update("UPDATE market_data_collection_batch SET collected_at=?,valid_until=? WHERE "+where,concat(new Object[]{now,valid},key))!=0)return;
        try {
            db.update("INSERT INTO market_data_collection_batch(product_id,dataset,adjust_type,requested_start_date,requested_end_date,"
                    +"data_start_date,data_end_date,record_count,source_result,provider,adapter_version,data_contract_version,content_hash,observed_dates_hash,collected_at,valid_until) "
                    +"VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",product,dataset,adjustment,start,end,dates.isEmpty()?null:dates.first(),
                    dates.isEmpty()?null:dates.last(),records.size(),result,manifest.get("provider"),manifest.get("adapterVersion"),policy.version(),content,
                    dates.isEmpty()?null:hash(dates.stream().map(LocalDate::toString).toList()),now,valid);
        } catch(org.springframework.dao.DuplicateKeyException duplicate) {
            if(db.queryForObject("SELECT COUNT(*) FROM market_data_collection_batch WHERE "+where,Integer.class,key)!=1)throw duplicate;
        }
    }

    public boolean covers(long product,String dataset,String adjustment,LocalDate start,LocalDate end) {
        if(start.isAfter(end))return true;
        LocalDate cursor=start;
        for(var window:windows(product,dataset,adjustment,start,end)) {
            if(window.start().isAfter(cursor))return false;
            if(!window.end().isBefore(cursor))cursor=window.end().plusDays(1);
            if(cursor.isAfter(end))return true;
        }
        return false;
    }
    public List<Window> windows(long product,String dataset,String adjustment,LocalDate start,LocalDate end) {
        if("REQUIRED".equals(adjustment)) {
            var raw=windows(product,dataset,"NONE",start,end);
            if(!"PRICE".equals(dataset))return raw;
            var p=db.queryForMap("SELECT product_type,market FROM investment_product WHERE id=?",product);
            String research=MarketDataContract.researchAdjustment(p.get("product_type").toString(),p.get("market").toString());
            if("NONE".equals(research))return raw;
            var adjusted=windows(product,dataset,research,start,end);
            var common=new ArrayList<Window>();
            for(var a:raw)for(var b:adjusted) {
                LocalDate first=a.start().isAfter(b.start())?a.start():b.start(),last=a.end().isBefore(b.end())?a.end():b.end();
                if(!first.isAfter(last))common.add(new Window(first,last));
            }
            return merge(common);
        }
        var policy=contract(product,dataset);
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        var rows=db.queryForList("SELECT * FROM market_data_collection_batch WHERE product_id=? AND dataset=? AND adjust_type=? "
                +"AND data_contract_version=? AND requested_start_date<=? AND requested_end_date>=? AND (valid_until IS NULL OR valid_until>?) "
                +"ORDER BY requested_start_date,requested_end_date DESC",product,dataset,adjustment,policy.version(),end,start,now);
        var result=new ArrayList<Window>();
        for(var row:rows) {
            LocalDate first=day(row.get("requested_start_date")),last=day(row.get("requested_end_date"));
            if("PRICE".equals(dataset)&&!matchesQuotes(product,adjustment,policy,first,last,row))continue;
            result.add(new Window(first,last));
        }
        return merge(result);
    }
    private static List<Window> merge(List<Window> values) {
        values.sort(Comparator.comparing(Window::start));
        var result=new ArrayList<Window>();
        for(var item:values) {
            if(result.isEmpty()||item.start().isAfter(result.get(result.size()-1).end().plusDays(1)))result.add(item);
            else {
                var old=result.remove(result.size()-1);
                result.add(new Window(old.start(),old.end().isAfter(item.end())?old.end():item.end()));
            }
        }
        return List.copyOf(result);
    }
    private boolean matchesQuotes(long product,String adjustment,MarketDataContract policy,LocalDate first,LocalDate last,Map<String,Object> batch) {
        String missing=String.join(" OR ",policy.quoteColumns().stream().map(column->column+" IS NULL").toList());
        if(db.queryForObject("SELECT COUNT(*) FROM product_daily_quote WHERE product_id=? AND adjust_type=? AND trade_date BETWEEN ? AND ? "
                +"AND (data_contract_version IS NULL OR data_contract_version<>? OR "+missing+")",Integer.class,product,adjustment,first,last,policy.version())>0)return false;
        if(!"DATA".equals(batch.get("source_result")))return true;
        var rows=db.queryForList("SELECT trade_date FROM product_daily_quote WHERE product_id=? AND adjust_type=? AND trade_date BETWEEN ? AND ? "
                +"AND data_contract_version=? ORDER BY trade_date",product,adjustment,first,last,policy.version());
        return rows.size()==((Number)batch.get("record_count")).intValue()
                &&hash(rows.stream().map(row->day(row.get("trade_date")).toString()).toList()).equals(batch.get("observed_dates_hash"));
    }
    private static LocalDate day(Object value) { return LocalDate.parse(value.toString().substring(0,10)); }
    private static Object[] concat(Object[] first,Object[] second) {
        var values=Arrays.copyOf(first,first.length+second.length);System.arraycopy(second,0,values,first.length,second.length);return values;
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))); }
        catch(Exception failure) { throw new IllegalStateException("采集记录散列失败",failure); }
    }
}
