package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smartfinance.agent.dto.AiBillAnalysisResponse;
import com.smartfinance.agent.dto.AiCandidateTransaction;
import com.smartfinance.agent.dto.BillConfirmRequest;
import com.smartfinance.agent.dto.BillImportResult;
import com.smartfinance.agent.entity.BillCandidateTransaction;
import com.smartfinance.agent.entity.BillImportRecord;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.mapper.BillCandidateTransactionMapper;
import com.smartfinance.agent.mapper.BillImportRecordMapper;
import com.smartfinance.agent.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillImportServiceImplTest {

    @Mock
    private BillAiClient billAiClient;

    @Mock
    private BillImportRecordMapper billImportRecordMapper;

    @Mock
    private BillCandidateTransactionMapper candidateMapper;

    @Mock
    private TransactionService transactionService;

    @Mock
    private MultipartFile file;

    private BillImportServiceImpl billImportService;

    @BeforeEach
    void setUp() {
        billImportService = new BillImportServiceImpl(
                billAiClient,
                billImportRecordMapper,
                candidateMapper,
                transactionService,
                "target/test-bill-uploads"
        );
    }

    @Test
    void importBill_whenHighConfidenceBill_shouldPersistRecordAndCandidates() throws Exception {
        mockUploadFile();
        AtomicReference<BillImportRecord> savedRecord = new AtomicReference<>();
        List<BillCandidateTransaction> savedCandidates = new ArrayList<>();
        mockRecordPersistence(savedRecord);
        mockCandidatePersistence(savedCandidates);
        when(billAiClient.analyze(file)).thenReturn(analysis("WECHAT", "0.92", List.of(candidate("35.00"))));

        BillImportResult result = billImportService.importBill(1L, file);

        assertThat(result.getStatus()).isEqualTo("ANALYZED");
        assertThat(result.getBillType()).isEqualTo("WECHAT");
        assertThat(result.getCandidates()).hasSize(1);
        assertThat(savedRecord.get().getConfidence()).isEqualByComparingTo("0.92");
        assertThat(savedCandidates.get(0).getAmount()).isEqualByComparingTo("35.00");
    }

    @Test
    void importBill_whenAiServiceFails_shouldNotCreateCandidates() throws Exception {
        mockUploadFile();
        AtomicReference<BillImportRecord> savedRecord = new AtomicReference<>();
        mockRecordPersistence(savedRecord);
        when(candidateMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        AiBillAnalysisResponse failed = new AiBillAnalysisResponse();
        failed.setBillType("ANALYSIS_FAILED");
        failed.getWarnings().add("AI service unavailable");
        when(billAiClient.analyze(file)).thenReturn(failed);

        BillImportResult result = billImportService.importBill(1L, file);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getCandidates()).isEmpty();
        verify(candidateMapper, never()).insert(any(BillCandidateTransaction.class));
        assertThat(savedRecord.get().getWarnings()).contains("AI service unavailable");
    }

    @Test
    void importBill_whenConfidenceIsLow_shouldSkipCandidates() throws Exception {
        mockUploadFile();
        AtomicReference<BillImportRecord> savedRecord = new AtomicReference<>();
        mockRecordPersistence(savedRecord);
        when(candidateMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(billAiClient.analyze(file)).thenReturn(analysis("ALIPAY", "0.42", List.of(candidate("18.00"))));

        BillImportResult result = billImportService.importBill(1L, file);

        assertThat(result.getStatus()).isEqualTo("LOW_CONFIDENCE");
        assertThat(result.getCandidates()).isEmpty();
        verify(candidateMapper, never()).insert(any(BillCandidateTransaction.class));
    }

    @Test
    void importBill_whenModelMarksNonBillButExtractsCandidates_shouldPersistAsUnknownAndKeepCandidates() throws Exception {
        mockUploadFile();
        AtomicReference<BillImportRecord> savedRecord = new AtomicReference<>();
        List<BillCandidateTransaction> savedCandidates = new ArrayList<>();
        mockRecordPersistence(savedRecord);
        mockCandidatePersistence(savedCandidates);
        AiBillAnalysisResponse analysis = analysis("NON_BILL", "0.87", List.of(candidate("15.00"), candidate("76.35")));
        analysis.setOcrText("收支记录页面，包含多笔交易。");
        when(billAiClient.analyze(file)).thenReturn(analysis);

        BillImportResult result = billImportService.importBill(1L, file);

        assertThat(result.getStatus()).isEqualTo("ANALYZED");
        assertThat(result.getBillType()).isEqualTo("UNKNOWN");
        assertThat(result.getCandidates()).hasSize(2);
        assertThat(savedRecord.get().getWarnings()).contains("已根据候选交易改为可确认导入");
    }

    @Test
    void confirm_shouldWriteSelectedCandidatesToTransactionTable() {
        BillImportRecord record = new BillImportRecord();
        record.setId(100L);
        record.setUserId(1L);
        record.setStatus("ANALYZED");
        when(billImportRecordMapper.selectById(100L)).thenReturn(record);

        BillCandidateTransaction candidate = existingCandidate(200L, 100L, 1L);
        when(candidateMapper.selectById(200L)).thenReturn(candidate);

        Transaction transaction = new Transaction();
        transaction.setId(300L);
        when(transactionService.add(
                eq(1L),
                eq(new BigDecimal("35.00")),
                eq("EXPENSE"),
                eq("food"),
                eq("lunch"),
                eq(LocalDate.parse("2026-06-01"))
        )).thenReturn(transaction);

        BillConfirmRequest request = new BillConfirmRequest();
        BillConfirmRequest.ConfirmCandidate item = new BillConfirmRequest.ConfirmCandidate();
        item.setId(200L);
        item.setSelected(true);
        item.setAmount(new BigDecimal("35.00"));
        item.setType("EXPENSE");
        item.setCategory("food");
        item.setDescription("lunch");
        item.setTransactionDate(LocalDate.parse("2026-06-01"));
        request.setCandidates(List.of(item));

        List<Transaction> imported = billImportService.confirm(1L, 100L, request);

        assertThat(imported).hasSize(1);
        assertThat(candidate.getStatus()).isEqualTo("CONFIRMED");
        assertThat(candidate.getTransactionId()).isEqualTo(300L);
        assertThat(record.getStatus()).isEqualTo("CONFIRMED");
        verify(candidateMapper).updateById(candidate);
        verify(billImportRecordMapper).updateById(record);
    }

    private void mockUploadFile() throws Exception {
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("wechat-bill.png");
        doNothing().when(file).transferTo(any(Path.class));
    }

    private void mockRecordPersistence(AtomicReference<BillImportRecord> savedRecord) {
        doAnswer(invocation -> {
            BillImportRecord record = invocation.getArgument(0);
            record.setId(100L);
            savedRecord.set(record);
            return 1;
        }).when(billImportRecordMapper).insert(any(BillImportRecord.class));
        when(billImportRecordMapper.selectById(100L)).thenAnswer(invocation -> savedRecord.get());
    }

    private void mockCandidatePersistence(List<BillCandidateTransaction> savedCandidates) {
        doAnswer(invocation -> {
            BillCandidateTransaction candidate = invocation.getArgument(0);
            candidate.setId(200L + savedCandidates.size());
            savedCandidates.add(candidate);
            return 1;
        }).when(candidateMapper).insert(any(BillCandidateTransaction.class));
        when(candidateMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> savedCandidates);
    }

    private AiBillAnalysisResponse analysis(String billType, String confidence, List<AiCandidateTransaction> candidates) {
        AiBillAnalysisResponse response = new AiBillAnalysisResponse();
        response.setBillType(billType);
        response.setConfidence(new BigDecimal(confidence));
        response.setOcrText("mock ocr text");
        response.setCandidates(candidates);
        return response;
    }

    private AiCandidateTransaction candidate(String amount) {
        AiCandidateTransaction candidate = new AiCandidateTransaction();
        candidate.setAmount(new BigDecimal(amount));
        candidate.setType("EXPENSE");
        candidate.setCategory("food");
        candidate.setDescription("lunch");
        candidate.setTransactionDate("2026-06-01");
        candidate.setConfidence(new BigDecimal("0.88"));
        return candidate;
    }

    private BillCandidateTransaction existingCandidate(Long id, Long billImportId, Long userId) {
        BillCandidateTransaction candidate = new BillCandidateTransaction();
        candidate.setId(id);
        candidate.setBillImportId(billImportId);
        candidate.setUserId(userId);
        candidate.setStatus("PENDING");
        return candidate;
    }
}
