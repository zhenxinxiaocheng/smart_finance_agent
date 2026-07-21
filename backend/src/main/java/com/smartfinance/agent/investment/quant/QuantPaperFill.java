package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("quant_paper_fill")
public class QuantPaperFill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private LocalDate fillDate;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal fee;
    private BigDecimal slippage;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
