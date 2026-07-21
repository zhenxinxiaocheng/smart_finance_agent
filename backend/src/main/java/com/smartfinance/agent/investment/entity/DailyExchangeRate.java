package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("daily_exchange_rate")
public class DailyExchangeRate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String baseCurrency;
    private String quoteCurrency;
    private LocalDate rateDate;
    private BigDecimal rate;
    private String source;
    private String adapterVersion;
    private LocalDateTime syncedAt;
}
