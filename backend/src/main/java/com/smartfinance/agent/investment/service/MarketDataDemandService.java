package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

/** Maps existing business ownership to bounded requirements; it never calls a provider. */
@Service
public class MarketDataDemandService {
    private final JdbcTemplate db;
    private final MarketDataRequirementService requirements;
    private final InvestmentHorizonService horizons;
    private final InvestmentHorizonProperties properties;
    @Value("${market-data.demand.detail-ttl:30m}")
    private Duration detailTtl=Duration.ofMinutes(30);
    private InvestmentQuoteAvailabilityService availability;
    @Autowired public void setAvailability(InvestmentQuoteAvailabilityService availability) { this.availability=availability; }
    private InvestmentDataQualityService qualityService;
    @Autowired public void setDataQuality(InvestmentDataQualityService qualityService) { this.qualityService=qualityService; }

    public MarketDataDemandService(JdbcTemplate db,MarketDataRequirementService requirements,
                                   InvestmentHorizonService horizons,InvestmentHorizonProperties properties) {
        this.db=db;this.requirements=requirements;this.horizons=horizons;this.properties=properties;
    }
    public LocalDate target(long product) {
        var row=db.queryForMap("SELECT product_type,market,fund_category,delisting_date FROM investment_product WHERE id=?",product);
        LocalDate end=requirements.now().toLocalDate().minusDays(Set.of("NYSE","NASDAQ","AMEX","US_INDEX").contains(String.valueOf(row.get("market")))?1:0);
        if(availability!=null) {
            var item=new InvestmentProduct(); item.setId(product);item.setMarket(String.valueOf(row.get("market")));
            item.setProductType(String.valueOf(row.get("product_type")));
            item.setFundCategory(row.get("fund_category")==null?null:String.valueOf(row.get("fund_category")));
            if(row.get("delisting_date")!=null)item.setDelistingDate(MarketDataRequirementService.day(row.get("delisting_date")));
            end=availability.target(item,requirements.now().toLocalDate(),requirements.now().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        }
        if(row.get("delisting_date")!=null){LocalDate last=MarketDataRequirementService.day(row.get("delisting_date"));if(last.isBefore(end))end=last;}
        return end;
    }
    public LocalDate boundedStart(long product,LocalDate start) {
        var rows=db.queryForList("SELECT listing_date,inception_date FROM investment_product WHERE id=?",product);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"产品不存在");
        for(Object value:rows.get(0).values())if(value!=null){LocalDate origin=MarketDataRequirementService.day(value);if(origin.isAfter(start))start=origin;}
        return start;
    }
    public LocalDate basicStart(long user,Long asset,long product,LocalDate end) {
        int history=InvestmentAnalysisServiceImpl.requiredHistoryDays(horizons.resolve(user,asset),properties);
        int days=InvestmentAnalysisServiceImpl.calendarLookbackDays(Math.max(history,properties.getIndicatorWarmupTradingDays()),properties);
        return boundedStart(product,end.minusDays(days));
    }
    @Transactional
    public Map<String,Object> prepareDetail(long user,long product) {
        boundedStart(product,requirements.now().toLocalDate()); // validate before target query
        LocalDate end=target(product),start=basicStart(user,null,product,end);
        if(!start.isAfter(end))requirements.save(user,"DETAIL",Long.toString(product),product,"PRICE","REQUIRED",start,end,true,requirements.now().plus(detailTtl));
        return Map.of("renewAfterMs",Math.max(1000,detailTtl.toMillis()/2),"expiresAt",requirements.now().plus(detailTtl));
    }
    @Transactional
    public void asset(long user,long asset,long product) {
        if(db.queryForObject("SELECT COUNT(*) FROM investment_asset WHERE id=? AND user_id=? AND product_id=? AND deleted=0",Integer.class,asset,user,product)==0)return;
        LocalDate end=target(product),start=basicStart(user,asset,product,end);
        if(!start.isAfter(end))requirements.save(user,"ASSET",Long.toString(asset),product,"PRICE","REQUIRED",start,end,true,null);
    }
    public void reconcileOwners() {
        for(var row:db.queryForList("SELECT id,user_id,product_id FROM investment_asset WHERE deleted=0 AND product_id IS NOT NULL"))
            asset(((Number)row.get("user_id")).longValue(),((Number)row.get("id")).longValue(),((Number)row.get("product_id")).longValue());
        for(var row:db.queryForList("SELECT id,user_id,index_code FROM investment_index_watchlist WHERE deleted=0")) {
            String[] key=String.valueOf(row.get("index_code")).split(":",2);
            if(key.length!=2||!Set.of("CN_INDEX","GLOBAL_INDEX").contains(key[0]))continue;
            String canonical=String.valueOf(row.get("index_code"));
            var products=db.queryForList("SELECT id FROM investment_product WHERE product_type='INDEX' AND code IN (?,?) AND market=? ORDER BY CASE WHEN code=? THEN 0 ELSE 1 END LIMIT 1",
                    canonical,key[1],"CN_INDEX".equals(key[0])?"CN_INDEX":"US_INDEX",canonical);
            if(products.isEmpty())continue;
            long product=((Number)products.get(0).get("id")).longValue(),user=((Number)row.get("user_id")).longValue();
            LocalDate end=target(product),start=basicStart(user,null,product,end);
            if(!start.isAfter(end))requirements.save(user,"INDEX_WATCH",row.get("id").toString(),product,"PRICE","REQUIRED",start,end,true,null);
        }
    }
    public boolean prepared(long user,String source,String owner,long product,LocalDate end) {
        var rows=db.queryForList("SELECT * FROM market_data_requirement r WHERE r.user_id=? AND r.source_type=? AND r.source_id=? AND r.product_id=? AND r.dataset='PRICE' AND "+MarketDataRequirementService.activePredicate("r"),user,source,owner,product,requirements.now());
        if(rows.isEmpty())return false;
        for(var row:rows) {
            if(row.get("prepared_start_date")==null||row.get("prepared_end_date")==null)return false;
            LocalDate start=MarketDataRequirementService.day(row.get("start_date"));
            LocalDate target=MarketDataRequirementService.day(row.get("target_date"));
            if(Boolean.TRUE.equals(row.get("follow_latest"))||"1".equals(String.valueOf(row.get("follow_latest"))))target=end;
            if(MarketDataRequirementService.day(row.get("prepared_start_date")).isAfter(start)||MarketDataRequirementService.day(row.get("prepared_end_date")).isBefore(target))return false;
        }
        return true;
    }
    public int recordCount(long product) {
        return db.queryForObject("SELECT COUNT(*) FROM product_daily_quote WHERE product_id=? AND adjust_type='NONE'",Integer.class,product);
    }
    public LocalDate publishedThrough(long user,String source,String owner,long product) {
        LocalDate through=null;
        var rows=db.queryForList("SELECT r.* FROM market_data_requirement r WHERE r.user_id=? AND r.source_type=? AND r.source_id=? AND r.product_id=? AND r.dataset='PRICE' AND "+MarketDataRequirementService.activePredicate("r"),user,source,owner,product,requirements.now());
        if(rows.isEmpty())return null;
        for(var row:rows) {
            if(row.get("prepared_start_date")==null||row.get("prepared_end_date")==null
                    ||MarketDataRequirementService.day(row.get("prepared_start_date")).isAfter(MarketDataRequirementService.day(row.get("start_date"))))return null;
            LocalDate last=MarketDataRequirementService.day(row.get("prepared_end_date"));
            if(last.isBefore(MarketDataRequirementService.day(row.get("start_date"))))return null;
            if(through==null||last.isBefore(through))through=last;
        }
        return through;
    }
    public boolean preparedForAnalysis(long user,long asset,long product) {
        var rows=db.queryForList("SELECT r.*,p.product_type,p.market FROM market_data_requirement r JOIN investment_product p ON p.id=r.product_id WHERE r.user_id=? AND r.source_type='ASSET' AND r.source_id=? AND r.product_id=? AND r.dataset='PRICE' AND "+MarketDataRequirementService.activePredicate("r"),user,Long.toString(asset),product,requirements.now());
        if(rows.isEmpty())return false;
        // Completeness receipts and readiness to analyze published samples are separate.
        // Analysis retains quality, return-series and per-horizon sample gates.
        for(var row:rows) {
            if(row.get("prepared_start_date")!=null&&row.get("prepared_end_date")!=null
                    &&!MarketDataRequirementService.day(row.get("prepared_start_date")).isAfter(MarketDataRequirementService.day(row.get("start_date"))))continue;
            boolean fund=Set.of("FUND","MUTUAL_FUND").contains(String.valueOf(row.get("product_type")));
            String adjustment=qualityService.adjustType(String.valueOf(row.get("product_type")),String.valueOf(row.get("market")));
            var sample=db.queryForMap("SELECT MIN(trade_date) first_date,COUNT(*) observations FROM product_daily_quote WHERE product_id=? AND adjust_type=? AND close_price>0"
                    +(fund?" AND total_return_index>0":""),product,adjustment);
            if(((Number)sample.get("observations")).intValue()<properties.getMinimumHistoryTradingDays()
                    ||sample.get("first_date")==null
                    ||MarketDataRequirementService.day(sample.get("first_date")).isAfter(MarketDataRequirementService.day(row.get("start_date"))))return false;
        }
        return true;
    }
}
