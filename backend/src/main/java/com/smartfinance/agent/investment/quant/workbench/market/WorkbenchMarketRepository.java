package com.smartfinance.agent.investment.quant.workbench.market;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Repository
public class WorkbenchMarketRepository {
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public WorkbenchMarketRepository(org.springframework.jdbc.core.JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> ownedAssets(Object user) {
        return db.queryForList(
                "SELECT a.id,a.product_id,p.name,p.code,p.product_type,p.market "
                        + "FROM investment_asset a JOIN investment_product p ON p.id=a.product_id "
                        + "WHERE a.user_id=? AND a.deleted=0 ORDER BY p.name",
                user);
    }

    public List<Map<String, Object>> products(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        return db.queryForList(
                "SELECT id AS product_id,product_type,market,fund_category,name,code FROM"
                        + " investment_product WHERE id IN ("
                        + marks
                        + ")",
                ids.toArray());
    }

    public List<Long> activeStockIds() {
        return db.queryForList(
                "SELECT id FROM investment_product WHERE product_type='STOCK' AND market IN"
                        + " ('SSE','SZSE','BSE') AND status='ACTIVE' ORDER BY id",
                Long.class);
    }

    public List<Map<String, Object>> activeStocks() {
        return db.queryForList(
                "SELECT id,code FROM investment_product WHERE product_type='STOCK' AND market IN"
                        + " ('SSE','SZSE','BSE') AND status='ACTIVE'");
    }

    public int insertMembershipSnapshot(
            String id,
            Object user,
            String universe,
            int revision,
            LocalDate asOf,
            Object capability,
            Object source,
            LocalDateTime created) {
        return db.update(
                "INSERT INTO"
                    + " quant_universe_snapshot(id,user_id,universe_id,revision,as_of_date,capability,source,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                id,
                user,
                universe,
                revision,
                asOf,
                capability,
                source,
                created);
    }

    public int[] insertMembership(List<Object[]> members) {
        return db.batchUpdate(
                "INSERT INTO quant_universe_member(snapshot_id,product_id) VALUES(?,?)", members);
    }

    public List<Map<String, Object>> ownedMembers(List<?> assetIds, Long user) {
        if (assetIds.isEmpty()) return List.of();
        String marks = String.join(",", Collections.nCopies(assetIds.size(), "?"));
        List<Object> args = new ArrayList<>(assetIds);
        args.add(user);
        return db.queryForList(
                "SELECT a.id,a.product_id,p.product_type,p.market,p.fund_category,p.name,p.code "
                        + "FROM investment_asset a JOIN investment_product p ON p.id=a.product_id "
                        + "WHERE a.id IN ("
                        + marks
                        + ") AND a.user_id=? AND a.deleted=0",
                args.toArray());
    }
}
