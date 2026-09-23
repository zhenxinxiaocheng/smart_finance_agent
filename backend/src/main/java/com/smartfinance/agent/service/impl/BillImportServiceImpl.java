package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.dto.AiBillAnalysisResponse;
import com.smartfinance.agent.dto.AiCandidateTransaction;
import com.smartfinance.agent.dto.BillCandidateDto;
import com.smartfinance.agent.dto.BillConfirmRequest;
import com.smartfinance.agent.dto.BillImportResult;
import com.smartfinance.agent.entity.BillCandidateTransaction;
import com.smartfinance.agent.entity.BillImportRecord;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.mapper.BillCandidateTransactionMapper;
import com.smartfinance.agent.mapper.BillImportRecordMapper;
import com.smartfinance.agent.service.BillImportService;
import com.smartfinance.agent.service.TransactionService;
import com.smartfinance.agent.service.BillConfirmationGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.HashSet;
import java.util.Objects;

@Service
public class BillImportServiceImpl implements BillImportService {

    private static final BigDecimal LOW_CONFIDENCE = new BigDecimal("0.60");

    private final BillAiClient billAiClient;
    private final BillImportRecordMapper billImportRecordMapper;
    private final BillCandidateTransactionMapper candidateMapper;
    private final TransactionService transactionService;
    private final BillConfirmationGuard confirmationGuard;
    private final Path uploadDir;

    public BillImportServiceImpl(BillAiClient billAiClient,
                                 BillImportRecordMapper billImportRecordMapper,
                                 BillCandidateTransactionMapper candidateMapper,
                                 TransactionService transactionService,
                                 BillConfirmationGuard confirmationGuard,
                                 @Value("${bill.upload-dir:uploads/bills}") String uploadDir) {
        this.billAiClient = billAiClient;
        this.billImportRecordMapper = billImportRecordMapper;
        this.candidateMapper = candidateMapper;
        this.transactionService = transactionService;
        this.confirmationGuard = confirmationGuard;
        this.uploadDir = Path.of(uploadDir);
    }

    @Override
    @Transactional
    public BillImportResult importBill(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("账单图片不能为空");
        }

        String storedPath = storeFile(userId, file);
        AiBillAnalysisResponse ai = billAiClient.analyze(file);
        ai = reconcileContradictoryAnalysis(ai);

        BillImportRecord record = new BillImportRecord();
        record.setUserId(userId);
        record.setOriginalFilename(file.getOriginalFilename());
        record.setFilePath(storedPath);
        record.setBillType(defaultText(ai == null ? null : ai.getBillType(), "UNKNOWN"));
        record.setConfidence(ai == null ? BigDecimal.ZERO : defaultDecimal(ai.getConfidence()));
        record.setOcrText(ai == null ? "" : defaultText(ai.getOcrText(), ""));
        record.setWarnings(joinWarnings(ai));
        record.setStatus(resolveStatus(record.getBillType(), record.getConfidence(), record.getWarnings()));
        billImportRecordMapper.insert(record);

        if ("ANALYZED".equals(record.getStatus())) {
            saveCandidates(userId, record.getId(), ai == null ? List.of() : ai.getCandidates());
        }

        return getById(userId, record.getId());
    }

    @Override
    public BillImportResult getById(Long userId, Long id) {
        BillImportRecord record = billImportRecordMapper.selectById(id);
        if (record == null || !record.getUserId().equals(userId)) {
            throw new IllegalArgumentException("账单导入记录不存在");
        }

        BillImportResult result = new BillImportResult();
        result.setId(record.getId());
        result.setOriginalFilename(record.getOriginalFilename());
        result.setBillType(record.getBillType());
        result.setConfidence(record.getConfidence());
        result.setOcrText(record.getOcrText());
        result.setWarnings(record.getWarnings());
        result.setStatus(record.getStatus());
        result.setCreatedAt(record.getCreatedAt());
        result.setCandidates(listCandidateDtos(userId, id));
        return result;
    }

    @Override
    @Transactional
    public List<Transaction> confirm(Long userId, Long id, BillConfirmRequest request) {
        if (request == null || request.getCandidates() == null || request.getCandidates().isEmpty())
            throw new IllegalArgumentException("候选交易不能为空");
        var ids = new HashSet<Long>();
        for (var item : request.getCandidates()) {
            if (item == null || item.getId() == null || !ids.add(item.getId()))
                throw new IllegalArgumentException("候选交易编号不能为空或重复");
            if (!Boolean.FALSE.equals(item.getSelected()) && (item.getAmount() == null
                    || item.getAmount().signum() <= 0 || item.getAmount().stripTrailingZeros().scale() > 2
                    || item.getAmount().compareTo(new BigDecimal("10000000000")) >= 0))
                throw new IllegalArgumentException("请输入有效金额，最多保留两位小数");
        }
        try (var lease = confirmationGuard.acquire(userId, id)) {
            return confirmLocked(userId, id, request);
        }
    }

    private List<Transaction> confirmLocked(Long userId, Long id, BillConfirmRequest request) {
        // SQLite requires a write before reading: its write transaction serializes confirmations.
        // MySQL additionally uses current locking reads to avoid an outer transaction's old snapshot.
        billImportRecordMapper.acquireWriteLock(userId, id);
        BillImportRecord record = billImportRecordMapper.selectOwnedForUpdate(userId, id);
        if (record == null || !record.getUserId().equals(userId)) {
            throw new IllegalArgumentException("账单导入记录不存在");
        }
        if (!"ANALYZED".equals(record.getStatus()) && !"CONFIRMED".equals(record.getStatus())) {
            throw new IllegalArgumentException("当前账单识别结果不可导入交易");
        }

        List<Transaction> imported = new ArrayList<>();
        for (BillConfirmRequest.ConfirmCandidate item : request.getCandidates()) {
            BillCandidateTransaction candidate = candidateMapper.selectOwnedForUpdate(userId, id, item.getId());
            if (candidate == null || !Objects.equals(candidate.getUserId(), userId)
                    || !Objects.equals(candidate.getBillImportId(), id))
                throw new IllegalArgumentException("候选交易不存在");
            if ("CONFIRMED".equals(candidate.getStatus()) || candidate.getTransactionId() != null) {
                // Never clear a confirmed link, even when a later request deselects this row.
                if (Boolean.FALSE.equals(item.getSelected())) continue;
                if (candidate.getTransactionId() == null || !sameConfirmation(candidate, item))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "该候选交易已导入，请到交易记录中修改");
                try { imported.add(transactionService.getById(candidate.getTransactionId(), userId)); }
                catch (IllegalArgumentException exception) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "该候选交易已导入，原交易已删除或不可用，请到交易记录中核对");
                }
                continue;
            }
            if (!"PENDING".equals(candidate.getStatus()) && !"IGNORED".equals(candidate.getStatus()))
                throw new IllegalArgumentException("候选交易状态不可确认");
            if (Boolean.FALSE.equals(item.getSelected())) {
                candidate.setStatus("IGNORED");
                candidateMapper.updateById(candidate);
                continue;
            }
            Transaction transaction = transactionService.add(
                    userId,
                    item.getAmount(),
                    item.getType(),
                    item.getCategory(),
                    item.getDescription(),
                    item.getTransactionDate()
            );
            imported.add(transaction);
            candidate.setAmount(item.getAmount());
            candidate.setType(item.getType());
            candidate.setCategory(item.getCategory());
            candidate.setDescription(item.getDescription());
            candidate.setTransactionDate(item.getTransactionDate());
            candidate.setStatus("CONFIRMED");
            candidate.setTransactionId(transaction.getId());
            candidateMapper.updateById(candidate);
        }
        record.setStatus("CONFIRMED");
        billImportRecordMapper.updateById(record);
        return imported;
    }

    private String storeFile(Long userId, MultipartFile file) {
        try {
            String original = file.getOriginalFilename() == null ? "bill-image" : file.getOriginalFilename();
            String suffix = "";
            int dot = original.lastIndexOf('.');
            if (dot >= 0 && dot < original.length() - 1) {
                suffix = original.substring(dot).replaceAll("[^a-zA-Z0-9.]", "");
            }
            Path userDir = uploadDir.resolve(String.valueOf(userId));
            Files.createDirectories(userDir);
            Path target = userDir.resolve(UUID.randomUUID() + suffix);
            file.transferTo(target);
            return target.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("账单图片保存失败：" + e.getMessage(), e);
        }
    }

    private void saveCandidates(Long userId, Long billImportId, List<AiCandidateTransaction> candidates) {
        if (candidates == null) return;
        for (AiCandidateTransaction ai : candidates) {
            if (ai.getAmount() == null || ai.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BillCandidateTransaction candidate = new BillCandidateTransaction();
            candidate.setBillImportId(billImportId);
            candidate.setUserId(userId);
            candidate.setAmount(ai.getAmount());
            candidate.setType(defaultText(ai.getType(), "EXPENSE"));
            candidate.setCategory(defaultText(ai.getCategory(), "其他"));
            candidate.setDescription(defaultText(ai.getDescription(), "账单识别导入"));
            candidate.setTransactionDate(parseDate(ai.getTransactionDate()));
            candidate.setConfidence(defaultDecimal(ai.getConfidence()));
            candidate.setStatus("PENDING");
            candidateMapper.insert(candidate);
        }
    }

    private List<BillCandidateDto> listCandidateDtos(Long userId, Long billImportId) {
        LambdaQueryWrapper<BillCandidateTransaction> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BillCandidateTransaction::getUserId, userId)
                .eq(BillCandidateTransaction::getBillImportId, billImportId)
                .orderByAsc(BillCandidateTransaction::getId);
        return candidateMapper.selectList(wrapper).stream().map(this::toDto).toList();
    }

    private BillCandidateDto toDto(BillCandidateTransaction candidate) {
        BillCandidateDto dto = new BillCandidateDto();
        dto.setId(candidate.getId());
        dto.setAmount(candidate.getAmount());
        dto.setType(candidate.getType());
        dto.setCategory(candidate.getCategory());
        dto.setDescription(candidate.getDescription());
        dto.setTransactionDate(candidate.getTransactionDate());
        dto.setConfidence(candidate.getConfidence());
        dto.setStatus(candidate.getStatus());
        dto.setTransactionId(candidate.getTransactionId());
        return dto;
    }

    private boolean sameConfirmation(BillCandidateTransaction candidate, BillConfirmRequest.ConfirmCandidate item) {
        return candidate.getAmount() != null && item.getAmount() != null
                && candidate.getAmount().compareTo(item.getAmount()) == 0
                && Objects.equals(candidate.getType(), item.getType())
                && Objects.equals(candidate.getCategory(), item.getCategory())
                && Objects.equals(candidate.getDescription(), item.getDescription())
                && Objects.equals(candidate.getTransactionDate(), item.getTransactionDate());
    }

    private String resolveStatus(String billType, BigDecimal confidence, String warnings) {
        if ("ANALYSIS_FAILED".equals(billType)) return "FAILED";
        if ("NON_BILL".equals(billType) || "LOW_QUALITY".equals(billType)) return "REJECTED";
        if (confidence == null || confidence.compareTo(LOW_CONFIDENCE) < 0) return "LOW_CONFIDENCE";
        return "ANALYZED";
    }

    private AiBillAnalysisResponse reconcileContradictoryAnalysis(AiBillAnalysisResponse ai) {
        if (ai == null || !"NON_BILL".equals(ai.getBillType()) || !hasValidCandidates(ai.getCandidates())) {
            return ai;
        }
        ai.setBillType("UNKNOWN");
        List<String> warnings = ai.getWarnings() == null ? new ArrayList<>() : new ArrayList<>(ai.getWarnings());
        warnings.add("模型同时识别出候选交易，已根据候选交易改为可确认导入，请人工核对后再入账。");
        ai.setWarnings(warnings);
        return ai;
    }

    private boolean hasValidCandidates(List<AiCandidateTransaction> candidates) {
        if (candidates == null) {
            return false;
        }
        return candidates.stream().anyMatch(candidate ->
                candidate != null
                        && candidate.getAmount() != null
                        && candidate.getAmount().compareTo(BigDecimal.ZERO) > 0
        );
    }

    private String joinWarnings(AiBillAnalysisResponse ai) {
        if (ai == null || ai.getWarnings() == null || ai.getWarnings().isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("；");
        ai.getWarnings().forEach(joiner::add);
        return joiner.toString();
    }

    private static BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? LocalDate.now() : LocalDate.parse(value);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }
}
