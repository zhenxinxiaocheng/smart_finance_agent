package com.smartfinance.agent.investment.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.smartfinance.agent.investment.marketdata.collection.MarketDataCollectionRepository;
import java.time.*;
import java.util.*;

/** Business demand only. Collection, checkpoints and retries belong to the existing jobs. */
@Service
public class MarketDataRequirementService {
    private static final Set<String> SOURCES=Set.of("ASSET","INDEX_WATCH","DETAIL","QUANT_TASK","DEPLOYMENT","FULL_LIBRARY");
    private final JdbcTemplate db;
    private final Clock clock;
    private final MarketDataCollectionRepository collections;
    @Autowired public MarketDataRequirementService(JdbcTemplate db,MarketDataCollectionRepository collections) {
        this(db,Clock.system(ZoneId.of("Asia/Shanghai")),collections);
    }
    public MarketDataRequirementService(JdbcTemplate db) { this(db,Clock.system(ZoneId.of("Asia/Shanghai"))); }
    public MarketDataRequirementService(JdbcTemplate db,Clock clock) { this(db,clock,new MarketDataCollectionRepository(db,clock)); }
    private MarketDataRequirementService(JdbcTemplate db,Clock clock,MarketDataCollectionRepository collections) {
        this.db=db;this.clock=clock;this.collections=collections;
    }
    public record Window(LocalDate start,LocalDate end) { }
    public LocalDateTime now() { return LocalDateTime.now(clock); }

    @Transactional
    public void save(long user,String source,String owner,long product,String dataset,String adjustment,
                     LocalDate start,LocalDate end,boolean followLatest,LocalDateTime expires) {
        if(!SOURCES.contains(source)||owner==null||owner.isBlank()||owner.length()>80
                ||dataset==null||!dataset.matches("[A-Z_]{1,20}")
                ||!Set.of("REQUIRED","NONE","QFQ","HFQ").contains(adjustment)
                ||start==null||end==null||start.isAfter(end)) throw new IllegalArgumentException("数据需求参数无效");
        if(db.update("UPDATE investment_product SET id=id WHERE id=?",product)!=1)
            throw new IllegalArgumentException("产品不存在");
        LocalDateTime time=now();
        int updated=db.update("UPDATE market_data_requirement SET start_date=?,target_date=?,follow_latest=?,"
                +"expires_at=?,released_at=NULL,revision=revision+1,updated_at=? WHERE user_id=? AND source_type=? "
                +"AND source_id=? AND product_id=? AND dataset=? AND adjust_type=? AND frequency='DAILY'",
                start,end,followLatest,expires,time,user,source,owner,product,dataset,adjustment);
        if(updated==0) db.update("INSERT INTO market_data_requirement(user_id,source_type,source_id,product_id,dataset,"
                +"adjust_type,start_date,target_date,follow_latest,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                user,source,owner,product,dataset,adjustment,start,end,followLatest,expires,time,time);
        refreshPreparedRow(db.queryForMap("SELECT * FROM market_data_requirement WHERE user_id=? AND source_type=? AND source_id=? AND product_id=? AND dataset=? AND adjust_type=? AND frequency='DAILY'",
                user,source,owner,product,dataset,adjustment));
    }
    public void release(long user,String source,String owner) {
        db.update("UPDATE market_data_requirement SET released_at=?,revision=revision+1,updated_at=? "
                +"WHERE user_id=? AND source_type=? AND source_id=? AND released_at IS NULL",now(),now(),user,source,owner);
    }

    /** SQL uses only trusted aliases and persisted business ownership, never client-supplied fragments. */
    public static String activePredicate(String r) {
        return r+".released_at IS NULL AND ("+r+".expires_at IS NULL OR "+r+".expires_at>?) AND ("
            +r+".source_type IN ('DETAIL','FULL_LIBRARY') OR ("+r+".source_type='ASSET' AND EXISTS(SELECT 1 FROM investment_asset a WHERE a.user_id="+r+".user_id AND "+ownerEquals("a.id",r)+" AND a.product_id="+r+".product_id AND a.deleted=0)) OR ("
            +r+".source_type='INDEX_WATCH' AND EXISTS(SELECT 1 FROM investment_index_watchlist w WHERE w.user_id="+r+".user_id AND "+ownerEquals("w.id",r)+" AND w.deleted=0)) OR ("
            +r+".source_type='QUANT_TASK' AND EXISTS(SELECT 1 FROM quant_v2_task t WHERE t.user_id="+r+".user_id AND "+ownerEquals("t.id",r)+" AND t.status IN ('QUEUED','RUNNING'))) OR ("
            +r+".source_type='DEPLOYMENT' AND EXISTS(SELECT 1 FROM quant_v2_deployment d WHERE d.user_id="+r+".user_id AND "+ownerEquals("d.id",r)+" AND d.status IN ('RUNNING','PAUSED','STOPPING'))))";
    }
    private static String ownerEquals(String id,String requirement) {
        // Cast both sides: MySQL connection and migrated columns can have different collations.
        // The SQL remains portable to SQLite; never coerce arbitrary owner strings to numbers.
        return "CAST("+id+" AS CHAR(80))=CAST("+requirement+".source_id AS CHAR(80))";
    }
    public record SqlScope(String sql,List<Object> arguments) { }
    public static String catalogExpression(String p) {
        return "COALESCE("+p+".catalog_market,CASE WHEN "+p+".market='FUND_CN' THEN 'FUND_CN' "
                +"WHEN "+p+".market IN ('CN_INDEX','US_INDEX') THEN 'INDEX' "
                +"WHEN "+p+".market IN ('SSE','SZSE','BSE') AND "+p+".product_type='ETF' THEN 'CN_ETF' "
                +"WHEN "+p+".market IN ('SSE','SZSE','BSE') THEN 'CN_A' ELSE 'US' END)";
    }
    public static String jobDataset(String j) {
        return "CASE WHEN "+j+".job_type IN ('BACKFILL','DAILY_UPDATE') THEN 'PRICE' ELSE "+j+".job_type END";
    }
    public static String businessDemand(String j) {
        return "EXISTS(SELECT 1 FROM market_data_requirement r WHERE r.product_id="+j+".product_id AND r.dataset=("
                +jobDataset(j)+") AND r.source_type<>'FULL_LIBRARY' AND (r.prepared_contract_version IS NULL OR r.prepared_start_date IS NULL OR r.prepared_end_date IS NULL "
                +"OR r.prepared_start_date>r.start_date OR r.prepared_end_date<r.target_date) AND "+activePredicate("r")+")";
    }
    public static String priorityExpression(String j) {
        return "COALESCE((SELECT MIN(CASE r.source_type WHEN 'DETAIL' THEN 0 WHEN 'ASSET' THEN 1 "
                +"WHEN 'INDEX_WATCH' THEN 1 WHEN 'FULL_LIBRARY' THEN 3 ELSE 2 END) FROM market_data_requirement r "
                +"WHERE r.product_id="+j+".product_id AND r.dataset=("+jobDataset(j)+") AND "+activePredicate("r")+"),3)";
    }
    public static SqlScope eligible(String j,String p,com.smartfinance.agent.investment.config.MarketDataScopeProperties scope,LocalDateTime now) {
        List<Object> args=new ArrayList<>();args.add(now);
        String sql="(EXISTS(SELECT 1 FROM market_data_requirement r WHERE r.product_id="+j+".product_id AND r.dataset=("
                +jobDataset(j)+") AND "+activePredicate("r")+")";
        if(scope.isEnabled()&&!scope.getMarkets().isEmpty()&&!scope.getDatasets().isEmpty()) {
            sql+=" OR ("+catalogExpression(p)+" IN ("+String.join(",",Collections.nCopies(scope.getMarkets().size(),"?"))
                    +") AND ("+jobDataset(j)+") IN ("+String.join(",",Collections.nCopies(scope.getDatasets().size(),"?"))+"))";
            args.addAll(scope.getMarkets());args.addAll(scope.getDatasets());
        }
        return new SqlScope(sql+")",args);
    }
    public List<Map<String,Object>> active() {
        for(var row:db.queryForList("SELECT r.* FROM market_data_requirement r WHERE "+activePredicate("r"),now()))refreshPreparedRow(row);
        return db.queryForList("SELECT r.* FROM market_data_requirement r WHERE "+activePredicate("r")
                +" ORDER BY CASE r.source_type WHEN 'DETAIL' THEN 0 WHEN 'ASSET' THEN 1 WHEN 'INDEX_WATCH' THEN 1 "
                +"WHEN 'FULL_LIBRARY' THEN 3 ELSE 2 END,r.product_id,r.start_date",now());
    }
    public List<Window> windows(long product,String dataset,LocalDate target) {
        target=clipEnd(product,target);
        var rows=db.queryForList("SELECT r.start_date,r.target_date,r.follow_latest FROM market_data_requirement r "
                +"WHERE r.product_id=? AND r.dataset=? AND "+activePredicate("r")+" ORDER BY r.start_date",product,dataset,now());
        List<Window> merged=new ArrayList<>();
        for(var row:rows) {
            LocalDate start=day(row.get("start_date"));
            Object flag=row.get("follow_latest");
            boolean follows=Boolean.TRUE.equals(flag)||"1".equals(String.valueOf(flag));
            LocalDate end=follows?target:day(row.get("target_date"));
            if(end.isAfter(target))end=target;
            if(start.isAfter(end))continue;
            if(!merged.isEmpty()&&!start.isAfter(merged.get(merged.size()-1).end().plusDays(1))) {
                Window last=merged.remove(merged.size()-1);
                merged.add(new Window(last.start(),last.end().isAfter(end)?last.end():end));
            } else merged.add(new Window(start,end));
        }
        return merged;
    }
    public static LocalDate day(Object value) { return LocalDate.parse(String.valueOf(value).substring(0,10)); }

    /** Prepared windows are acknowledgements for a business request, not a claim of full-history quality. */
    public List<Window> missingWindows(long product,String dataset,LocalDate target) {
        target=clipEnd(product,target);
        List<Window> gaps=new ArrayList<>();
        for(var row:db.queryForList("SELECT r.* FROM market_data_requirement r WHERE r.product_id=? AND r.dataset=? AND "
                +activePredicate("r"),product,dataset,now())) {
            LocalDate start=day(row.get("start_date"));
            boolean follows=Boolean.TRUE.equals(row.get("follow_latest"))||"1".equals(String.valueOf(row.get("follow_latest")));
            LocalDate end=follows?target:day(row.get("target_date"));
            if(end.isAfter(target))end=target;
            if(start.isAfter(end))continue;
            LocalDate cursor=start;
            for(var receipt:collections.windows(product,dataset,row.get("adjust_type").toString(),start,end)) {
                if(receipt.end().isBefore(cursor))continue;
                if(receipt.start().isAfter(end))break;
                if(receipt.start().isAfter(cursor))gaps.add(new Window(cursor,receipt.start().minusDays(1)));
                if(!receipt.end().isBefore(cursor))cursor=receipt.end().plusDays(1);
                if(cursor.isAfter(end))break;
            }
            if(!cursor.isAfter(end))gaps.add(new Window(cursor,end));
        }
        gaps.sort(Comparator.comparing(Window::start));
        List<Window> result=new ArrayList<>();
        for(Window gap:gaps) {
            if(result.isEmpty()||gap.start().isAfter(result.get(result.size()-1).end().plusDays(1)))result.add(gap);
            else { Window last=result.remove(result.size()-1);result.add(new Window(last.start(),last.end().isAfter(gap.end())?last.end():gap.end())); }
        }
        return result;
    }
    private LocalDate clipEnd(long product,LocalDate target) {
        var values=db.queryForList("SELECT delisting_date FROM investment_product WHERE id=?",product);
        if(!values.isEmpty()&&values.get(0).get("delisting_date")!=null) {
            LocalDate end=day(values.get(0).get("delisting_date"));if(end.isBefore(target))return end;
        }
        return target;
    }
    @Transactional
    public void recordPrepared(long product,String dataset,LocalDate start,LocalDate end) {
        if(start==null||end==null||start.isAfter(end))return;
        db.update("UPDATE investment_product SET id=id WHERE id=?",product);
        for(var row:db.queryForList("SELECT r.* FROM market_data_requirement r WHERE r.product_id=? AND r.dataset=? AND "
                +activePredicate("r"),product,dataset,now())) {
            refreshPreparedRow(row);
        }
    }

    public boolean covers(Map<String,Object> row,LocalDate end) {
        return collections.covers(((Number)row.get("product_id")).longValue(),row.get("dataset").toString(),
                row.get("adjust_type").toString(),day(row.get("start_date")),end);
    }
    public String currentVersion(long product,String dataset) { return collections.currentVersion(product,dataset); }
    public void recordCollection(long product,String dataset,String adjustment,LocalDate start,LocalDate end,Map<String,Object> response) {
        collections.record(product,dataset,adjustment,start,end,response);
    }
    private void refreshPreparedRow(Map<String,Object> row) {
        long product=((Number)row.get("product_id")).longValue();String dataset=row.get("dataset").toString();
        LocalDate start=day(row.get("start_date")),end=day(row.get("target_date"));
        com.smartfinance.agent.investment.marketdata.collection.MarketDataCollectionRepository.Window first=null;
        for(var item:collections.windows(product,dataset,row.get("adjust_type").toString(),start,end))
            if(!item.start().isAfter(start)&&!item.end().isBefore(start)){first=item;break;}
        db.update("UPDATE market_data_requirement SET prepared_start_date=?,prepared_end_date=?,prepared_contract_version=? WHERE id=? AND revision=?",
                first==null?null:first.start(),first==null?null:first.end(),first==null?null:collections.currentVersion(product,dataset),row.get("id"),row.get("revision"));
    }
}
