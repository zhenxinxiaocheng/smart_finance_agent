package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("investment_index_display_order")
public class InvestmentIndexDisplayOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String indexCode;
    private Integer sortOrder;
}
