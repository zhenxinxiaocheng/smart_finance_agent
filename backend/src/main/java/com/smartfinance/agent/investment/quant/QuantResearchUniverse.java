package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_research_universe")
public class QuantResearchUniverse {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String universeCode;
    private String name;
    private String modelFamily;
    private String market;
    private String selectionRuleJson;
    private String datasetVersion;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
