package com.smartfinance.agent.service;

import com.smartfinance.agent.dto.BillConfirmRequest;
import com.smartfinance.agent.dto.BillImportResult;
import com.smartfinance.agent.entity.Transaction;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface BillImportService {
    BillImportResult importBill(Long userId, MultipartFile file);

    BillImportResult getById(Long userId, Long id);

    List<Transaction> confirm(Long userId, Long id, BillConfirmRequest request);
}
