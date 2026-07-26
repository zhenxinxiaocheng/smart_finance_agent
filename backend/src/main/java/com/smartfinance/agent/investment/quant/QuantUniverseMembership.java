package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("quant_universe_membership")
public class QuantUniverseMembership {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long universeId;
    private Long productId;
    private String productType;
    private String market;
    private String code;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String sourceSnapshot;
    private LocalDateTime createdAt;
}
