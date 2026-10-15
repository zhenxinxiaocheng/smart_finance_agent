package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.marketdata.collection.MarketDataCollectionRepository;
import com.smartfinance.agent.investment.marketdata.contract.MarketDataContract;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Source-response fixtures for queue tests; production never uses this assembler. */
final class MarketDataTestSupport {
    private MarketDataTestSupport() { }

    static void collected(JdbcTemplate db,long product,LocalDate start,LocalDate end,boolean hasObservations) {
        var p=db.queryForMap("SELECT product_type,market FROM investment_product WHERE id=?",product);
        String type=p.get("product_type").toString(),market=p.get("market").toString();
        String research=MarketDataContract.researchAdjustment(type,market);
        var adjustments=new LinkedHashSet<>(List.of("NONE",research));
        var collections=new MarketDataCollectionRepository(db);
        String contract=MarketDataContract.forProduct("PRICE",type).version();
        for(String adjustment:adjustments) {
            if(hasObservations) {
                if(db.queryForObject("SELECT COUNT(*) FROM product_daily_quote WHERE product_id=? AND adjust_type=? AND trade_date BETWEEN ? AND ?",Integer.class,product,adjustment,start,end)==0)
                    db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,open_price,high_price,low_price,close_price,volume,total_return_index,source,data_contract_version) VALUES(?,?,?,10,10,10,10,100,10,'TEST',?)",product,end,adjustment,contract);
                db.update("UPDATE product_daily_quote SET data_contract_version=?,open_price=COALESCE(open_price,close_price),high_price=COALESCE(high_price,close_price),low_price=COALESCE(low_price,close_price),volume=COALESCE(volume,100) WHERE product_id=? AND adjust_type=? AND trade_date BETWEEN ? AND ?",contract,product,adjustment,start,end);
            }
            List<Map<String,Object>> rows=db.queryForList("SELECT trade_date,open_price,high_price,low_price,close_price,volume FROM product_daily_quote WHERE product_id=? AND adjust_type=? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",product,adjustment,start,end);
            var records=new ArrayList<Map<String,Object>>();
            if(hasObservations)for(var row:rows)records.add(Map.of("data_date",row.get("trade_date").toString(),"open",row.get("open_price"),"high",row.get("high_price"),"low",row.get("low_price"),"close",row.get("close_price"),"volume",row.get("volume")));
            collections.record(product,"PRICE",adjustment,start,end,Map.of("dataContractVersion",contract,"records",records,"result",records.isEmpty()?"EMPTY":"DATA","provider","TEST","adapterVersion","fixture"));
        }
    }
}
