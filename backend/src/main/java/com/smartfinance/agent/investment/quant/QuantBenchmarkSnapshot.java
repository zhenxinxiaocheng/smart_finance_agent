package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("quant_benchmark_snapshot")
public class QuantBenchmarkSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String snapshotVersion;
    private Long benchmarkProfileId;
    private String benchmarkCode;
    private String sourceUri;
    private String sourceVersion;
    private String provider;
    private String adapterVersion;
    private String currency;
    private String fxRule;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private LocalDate sampleStartDate;
    private LocalDate sampleEndDate;
    private LocalDateTime fetchedAt;
    private String recordsJson;
    private LocalDateTime createdAt;
}
