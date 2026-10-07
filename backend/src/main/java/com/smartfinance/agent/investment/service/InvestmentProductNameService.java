package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Optional source names enrich the existing official catalog without creating securities. */
@Service
public class InvestmentProductNameService {
    private final JdbcTemplate db;
    private final AnalysisServiceClient analysis;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String,NameLookup> lookups = new ConcurrentHashMap<>();
    @Value("${market-data.search.name-cache-ttl:24h}") private Duration cacheTtl = Duration.ofHours(24);
    @Value("${market-data.search.name-failure-backoff:1m}") private Duration failureBackoff = Duration.ofMinutes(1);
    @Value("${market-data.search.name-cache-max-entries:512}") private int maxEntries = 512;

    public InvestmentProductNameService(JdbcTemplate db, AnalysisServiceClient analysis, ObjectMapper json) {
        this.db = db; this.analysis = analysis; this.json = json;
    }

    public void enrich(String search) {
        if (search == null || search.length() < 2 || search.length() > 80
                || search.codePoints().noneMatch(character -> character > 127)) return;
        Instant now = Instant.now();
        NameLookup cached = lookups.get(search);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            if (cached.names() != null) apply(cached.names());
            return;
        }
        if (lookups.size() >= maxEntries) lookups.clear();
        NameLookup pending = new NameLookup(now.plus(failureBackoff), null);
        if (cached == null ? lookups.putIfAbsent(search, pending) != null
                : !lookups.replace(search, cached, pending)) return;
        try {
            List<Map<String,Object>> names = analysis.marketProductNames(search);
            apply(names);
            lookups.put(search, new NameLookup(Instant.now().plus(cacheTtl), names));
        } catch (Exception unavailable) {
            lookups.put(search, new NameLookup(Instant.now().plus(failureBackoff), null));
            // Name lookup is optional; local code/name searches remain available.
        }
    }

    private void apply(List<Map<String,Object>> names) {
        try {
            for (Map<String,Object> match : names) {
                Object symbol = match.get("code");
                if (!(symbol instanceof String code) || !code.matches("[A-Z][A-Z0-9.-]{0,39}")) continue;
                if (!(match.get("aliases") instanceof List<?> sourceNames)) continue;
                for (Map<String,Object> product : db.queryForList("SELECT id,name,name_aliases FROM investment_product "
                        + "WHERE code=? AND market IN ('NASDAQ','NYSE','AMEX') AND status='ACTIVE'", code)) {
                    LinkedHashSet<String> aliases = new LinkedHashSet<>();
                    aliases.add(String.valueOf(product.get("name")));
                    if (product.get("name_aliases") != null)
                        aliases.addAll(json.readValue(String.valueOf(product.get("name_aliases")), new TypeReference<List<String>>() {}));
                    sourceNames.stream().filter(String.class::isInstance).map(String.class::cast)
                            .map(String::trim).filter(name -> !name.isEmpty() && name.length() <= 512).limit(16).forEach(aliases::add);
                    String merged = json.writeValueAsString(aliases);
                    if (!merged.equals(product.get("name_aliases")))
                        db.update("UPDATE investment_product SET name_aliases=? WHERE id=?", merged, product.get("id"));
                }
            }
        } catch (Exception unavailable) {
            // Name lookup is optional; local code/name searches remain available.
        }
    }

    private record NameLookup(Instant expiresAt, List<Map<String,Object>> names) {}
}
