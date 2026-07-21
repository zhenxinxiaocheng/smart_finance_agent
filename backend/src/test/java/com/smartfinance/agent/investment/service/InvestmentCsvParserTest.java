package com.smartfinance.agent.investment.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestmentCsvParserTest {

    private final InvestmentCsvParser parser = new InvestmentCsvParser();

    @Test
    void parse_shouldKeepValidRowsAndReportInvalidRows() {
        String csv = "accountId,eventType,tradeDate,market,productType,code,name,currency,quantity,price,amount,fee\n"
                + "1,BUY,2026-07-10,SSE,STOCK,600519,贵州茅台,CNY,10,1500,,5\n"
                + "1,SELL,bad-date,SSE,STOCK,600519,贵州茅台,CNY,2,1600,,5\n";

        var rows = parser.parse(csv.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, rows.size());
        assertTrue(rows.get(0).isValid());
        assertEquals("600519", rows.get(0).getTransaction().getProduct().getCode());
        assertEquals(1, rows.get(1).getErrors().size());
    }
}
