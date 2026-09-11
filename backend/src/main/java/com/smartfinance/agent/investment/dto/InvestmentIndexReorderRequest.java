package com.smartfinance.agent.investment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record InvestmentIndexReorderRequest(
        @NotEmpty
        @Size(max = 100)
        List<@NotBlank String> indexCodes
) {
}
