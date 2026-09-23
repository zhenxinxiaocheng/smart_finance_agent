package com.smartfinance.agent.service;

import com.smartfinance.agent.dto.BillConfirmRequest;
import com.smartfinance.agent.entity.BillImportRecord;
import com.smartfinance.agent.entity.BillCandidateTransaction;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.mapper.BillImportRecordMapper;
import com.smartfinance.agent.mapper.BillCandidateTransactionMapper;
import com.smartfinance.agent.mapper.TransactionMapper;
import com.smartfinance.agent.service.impl.BillAiClient;
import com.smartfinance.agent.service.impl.BillImportServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ServiceIntegrationTestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:bill_confirmation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=never", "bill.confirmation-guard.enabled=false"
})
@Import({BillImportServiceImpl.class, BillConfirmationGuard.class,
        com.smartfinance.agent.config.BillConfirmationGuardProperties.class})
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BillConfirmationIntegrationTest {
    @Autowired private BillImportService bills;
    @Autowired private BillImportRecordMapper records;
    @Autowired private BillCandidateTransactionMapper candidates;
    @Autowired private TransactionMapper transactions;
    @Autowired private TransactionService transactionService;
    @Autowired private com.smartfinance.agent.mapper.UserMapper users;
    @MockBean private BillAiClient ai;

    @Test
    void repeatedAndConcurrentConfirmationsCreateOneTransactionWithoutRedis() throws Exception {
        var candidate = prepare();
        var request = request(candidate.getId());
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<List<Transaction>> call = () -> {
                start.await();
                return bills.confirm(7L, candidate.getBillImportId(), request);
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            start.countDown();
            var one = first.get(10, TimeUnit.SECONDS);
            var two = second.get(10, TimeUnit.SECONDS);
            assertThat(one).hasSize(1);
            assertThat(two).extracting(Transaction::getId).containsExactly(one.get(0).getId());
            assertThat(transactions.selectCount(null)).isEqualTo(1);
            var replay = bills.confirm(7L, candidate.getBillImportId(), request);
            assertThat(replay).extracting(Transaction::getId).containsExactly(one.get(0).getId());
            assertThat(transactions.selectCount(null)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void invalidCandidateRollsBackEarlierRowsAndCanBeRetried() {
        var candidate = prepare();
        var valid = request(candidate.getId()).getCandidates().get(0);
        var invalid = request(Long.MAX_VALUE).getCandidates().get(0);
        var request = new BillConfirmRequest();
        request.setCandidates(List.of(valid, invalid));
        assertThatThrownBy(() -> bills.confirm(7L, candidate.getBillImportId(), request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transactions.selectCount(null)).isZero();
        assertThat(candidates.selectById(candidate.getId()).getTransactionId()).isNull();
        assertThat(records.selectById(candidate.getBillImportId()).getStatus()).isEqualTo("ANALYZED");
        assertThat(bills.confirm(7L, candidate.getBillImportId(), request(candidate.getId()))).hasSize(1);
    }

    @Test
    void anotherUserCannotConfirmAndDeletedTransactionIsNeverRecreated() {
        var candidate = prepare();
        var request = request(candidate.getId());
        assertThatThrownBy(() -> bills.confirm(8L, candidate.getBillImportId(), request))
                .isInstanceOf(IllegalArgumentException.class);
        var imported = bills.confirm(7L, candidate.getBillImportId(), request);
        transactionService.delete(imported.get(0).getId(), 7L);
        assertThatThrownBy(() -> bills.confirm(7L, candidate.getBillImportId(), request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(transactions.selectCount(null)).isZero();
        assertThat(candidates.selectById(candidate.getId()).getTransactionId()).isEqualTo(imported.get(0).getId());
    }

    @Test
    void clearingCandidateDescriptionIsPersistedAndCanBeReplayed() {
        var candidate = prepare();
        candidate.setDescription("original description");
        candidates.updateById(candidate);
        var request = request(candidate.getId());
        request.getCandidates().get(0).setDescription(null);
        var first = bills.confirm(7L, candidate.getBillImportId(), request);
        assertThat(candidates.selectById(candidate.getId()).getDescription()).isNull();
        assertThat(bills.confirm(7L, candidate.getBillImportId(), request))
                .extracting(Transaction::getId).containsExactly(first.get(0).getId());
    }

    private BillCandidateTransaction prepare() {
        var user = new com.smartfinance.agent.entity.User();
        user.setId(7L);
        user.setUsername("bill-test");
        user.setPassword("test-only");
        users.insert(user);
        var record = new BillImportRecord();
        record.setUserId(7L);
        record.setOriginalFilename("bill.png");
        record.setFilePath("test/bill.png");
        record.setStatus("ANALYZED");
        records.insert(record);
        var candidate = new BillCandidateTransaction();
        candidate.setUserId(7L);
        candidate.setBillImportId(record.getId());
        candidate.setAmount(new BigDecimal("12.34"));
        candidate.setType("EXPENSE");
        candidate.setCategory("food");
        candidate.setTransactionDate(LocalDate.of(2026, 9, 22));
        candidate.setStatus("PENDING");
        candidates.insert(candidate);
        return candidate;
    }

    private BillConfirmRequest request(Long id) {
        var item = new BillConfirmRequest.ConfirmCandidate();
        item.setId(id);
        item.setAmount(new BigDecimal("15.67"));
        item.setType("EXPENSE");
        item.setCategory("food");
        item.setDescription("confirmed amount");
        item.setTransactionDate(LocalDate.of(2026, 9, 22));
        var request = new BillConfirmRequest();
        request.setCandidates(List.of(item));
        return request;
    }
}
