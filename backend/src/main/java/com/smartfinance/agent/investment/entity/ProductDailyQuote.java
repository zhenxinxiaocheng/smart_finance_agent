package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("product_daily_quote")
public class ProductDailyQuote {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private LocalDate tradeDate;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal totalReturnIndex;
    private BigDecimal previousClose;
    private BigDecimal changeAmount;
    private BigDecimal changePercent;
    private BigDecimal volume;
    private BigDecimal amount;
    private BigDecimal turnoverRate;
    private BigDecimal volumeRatio;
    private BigDecimal amplitude;
    private String adjustType;
    private String source;
    private String adapterVersion;
    private LocalDateTime syncedAt;
}
