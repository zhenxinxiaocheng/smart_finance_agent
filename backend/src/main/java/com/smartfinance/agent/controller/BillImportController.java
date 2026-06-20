package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.BillConfirmRequest;
import com.smartfinance.agent.dto.BillImportResult;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.service.BillImportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/bills")
public class BillImportController {

    private final BillImportService billImportService;

    public BillImportController(BillImportService billImportService) {
        this.billImportService = billImportService;
    }

    @PostMapping("/import")
    public Result<BillImportResult> importBill(@RequestAttribute Long userId,
                                               @RequestPart("file") MultipartFile file) {
        return Result.success(billImportService.importBill(userId, file));
    }

    @GetMapping("/{id}")
    public Result<BillImportResult> getById(@RequestAttribute Long userId,
                                            @PathVariable Long id) {
        return Result.success(billImportService.getById(userId, id));
    }

    @PostMapping("/{id}/confirm")
    public Result<List<Transaction>> confirm(@RequestAttribute Long userId,
                                             @PathVariable Long id,
                                             @Valid @RequestBody BillConfirmRequest request) {
        return Result.success(billImportService.confirm(userId, id, request));
    }
}
