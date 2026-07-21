package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.dto.InvestmentImportRow;
import com.smartfinance.agent.investment.dto.InvestmentProductRequest;
import com.smartfinance.agent.investment.dto.InvestmentTransactionRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

public class InvestmentCsvParser {

    private static final Set<String> CASH_EVENTS = Set.of("DEPOSIT", "WITHDRAWAL", "FEE");

    public List<InvestmentImportRow> parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\n");
        if (lines.length < 2) {
            throw new IllegalArgumentException("CSV 至少需要表头和一行数据");
        }
        List<String> headers = parseLine(lines[0]).stream().map(String::trim).toList();
        requireHeaders(headers, "accountId", "eventType", "tradeDate", "currency");
        List<InvestmentImportRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            rows.add(parseRow(i + 1, headers, parseLine(lines[i])));
        }
        return rows;
    }

    private InvestmentImportRow parseRow(int rowNumber, List<String> headers, List<String> values) {
        InvestmentImportRow row = new InvestmentImportRow();
        row.setRowNumber(rowNumber);
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            data.put(headers.get(i), i < values.size() ? values.get(i).trim() : "");
        }
        InvestmentTransactionRequest request = new InvestmentTransactionRequest();
        row.setTransaction(request);
        try {
            request.setAccountId(Long.valueOf(required(data, "accountId")));
            request.setEventType(required(data, "eventType").toUpperCase(Locale.ROOT));
            request.setTradeDate(LocalDate.parse(required(data, "tradeDate")));
            request.setSettlementDate(date(data.get("settlementDate")));
            request.setCurrency(defaultText(data.get("currency"), "CNY").toUpperCase(Locale.ROOT));
            request.setQuantity(decimal(data.get("quantity")));
            request.setPrice(decimal(data.get("price")));
            request.setAmount(decimal(data.get("amount")));
            request.setFee(defaultDecimal(data.get("fee")));
            request.setFactor(decimal(data.get("factor")));
            request.setSource("CSV");
            request.setExternalRef(emptyToNull(data.get("externalRef")));
            request.setNote(emptyToNull(data.get("note")));
            if (!CASH_EVENTS.contains(request.getEventType())) {
                InvestmentProductRequest product = new InvestmentProductRequest();
                product.setMarket(required(data, "market").toUpperCase(Locale.ROOT));
                product.setProductType(required(data, "productType").toUpperCase(Locale.ROOT));
                product.setCode(required(data, "code").toUpperCase(Locale.ROOT));
                product.setName(required(data, "name"));
                product.setCurrency(request.getCurrency());
                request.setProduct(product);
            }
        } catch (RuntimeException ex) {
            row.getErrors().add(ex.getMessage());
        }
        return row;
    }

    private static List<String> parseLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static void requireHeaders(List<String> headers, String... required) {
        for (String name : required) {
            if (!headers.contains(name)) {
                throw new IllegalArgumentException("CSV 缺少表头：" + name);
            }
        }
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少字段：" + key);
        }
        return value.trim();
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
    }

    private static BigDecimal defaultDecimal(String value) {
        BigDecimal parsed = decimal(value);
        return parsed == null ? BigDecimal.ZERO : parsed;
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
