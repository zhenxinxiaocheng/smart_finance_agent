package com.smartfinance.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
public class BillConfirmRequest {
    @Valid
    @NotEmpty(message = "候选交易不能为空")
    private List<ConfirmCandidate> candidates = new ArrayList<>();

    @Data
    @EqualsAndHashCode(callSuper = false)
    public static class ConfirmCandidate extends TransactionRequest {
        private Long id;
        private Boolean selected = true;
    }
}
