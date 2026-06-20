package com.smartfinance.agent.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class BillImportResult {
    private Long id;
    private String originalFilename;
    private String billType;
    private BigDecimal confidence;
    private String ocrText;
    private String warnings;
    private String status;
    private LocalDateTime createdAt;
    private List<BillCandidateDto> candidates = new ArrayList<>();
}
