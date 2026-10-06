package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Same publication policy used by Python data quality; no asset-code exceptions. */
@Service
public class InvestmentQuoteAvailabilityService {
    private static final Logger log=LoggerFactory.getLogger(InvestmentQuoteAvailabilityService.class);
    private final AnalysisServiceClient client;
    @Value("${market-data.availability.cache-ttl:1m}") private Duration cacheTtl=Duration.ofMinutes(1);
    private final Map<Scope,Cached> cache=new ConcurrentHashMap<>();
    private record Scope(String type,String market,String category) { }
    private record Cached(LocalDate requested,Instant expires,LocalDate target) { }
    public InvestmentQuoteAvailabilityService(AnalysisServiceClient client) { this.client=client; }
    public LocalDate target(InvestmentProduct product,LocalDate requested,Instant at) {
        var scope=new Scope(product.getProductType(),product.getMarket(),product.getFundCategory());
        Cached result=cache.get(scope);
        if(result==null||!result.requested().equals(requested)||!result.expires().isAfter(at)) {
            var category=new InvestmentProduct();category.setProductType(product.getProductType());
            category.setMarket(product.getMarket());category.setFundCategory(product.getFundCategory());
            LocalDate target;
            try {
                Map<String,Object> response=client.quoteAvailability(category,requested.minusYears(1),requested,at);
                target=LocalDate.parse(String.valueOf(response.get("targetDate")));
            } catch(RuntimeException unavailable) {
                // This is only a conservative request bound, never completeness/holiday evidence.
                // Calendar/provider outages must not abort ApplicationReadyEvent or the queue.
                log.warn("Publication calendar unavailable for {} {}",scope.market(),scope.category());
                target=result==null?requested.minusDays(1):result.target();
            }
            result=new Cached(requested,at.plus(cacheTtl),target.isAfter(requested)?requested:target);
            cache.put(scope,result);
        }
        LocalDate end=result.target();
        return product.getDelistingDate()!=null&&product.getDelistingDate().isBefore(end)?product.getDelistingDate():end;
    }
}
