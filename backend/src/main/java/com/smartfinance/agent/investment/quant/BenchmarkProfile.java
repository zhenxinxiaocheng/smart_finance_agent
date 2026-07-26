package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("benchmark_profile")
public class BenchmarkProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String productType;
    private String productCode;
    private String modelFamily;
    private String benchmarkCode;
    private String displayName;
    private String compositionJson;
    private String currency;
    private String fxRule;
    private String sourceUri;
    private String sourceVersion;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
