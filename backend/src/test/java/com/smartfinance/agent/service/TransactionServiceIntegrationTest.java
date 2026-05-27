package com.smartfinance.agent.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.entity.User;
import com.smartfinance.agent.mapper.TransactionMapper;
import com.smartfinance.agent.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ServiceIntegrationTestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:transaction_service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "mybatis-plus.global-config.db-config.logic-delete-field=deleted",
        "mybatis-plus.global-config.db-config.logic-delete-value=1",
        "mybatis-plus.global-config.db-config.logic-not-delete-value=0"
})
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TransactionServiceIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionMapper transactionMapper;

    @Autowired
    private UserMapper userMapper;

    @Test
    void addAndGetById_shouldPersistTransactionForUser() {
        Long userId = createUser("alice");

        Transaction transaction = transactionService.add(
                userId,
                new BigDecimal("88.50"),
                "EXPENSE",
                "food",
                "lunch",
                LocalDate.parse("2026-05-27")
        );

        Transaction saved = transactionService.getById(transaction.getId(), userId);
        assertThat(saved.getAmount()).isEqualByComparingTo("88.50");
        assertThat(saved.getCategory()).isEqualTo("food");
        assertThat(saved.getDeleted()).isZero();
    }

    @Test
    void update_shouldChangeExistingTransaction() {
        Long userId = createUser("alice");
        Transaction transaction = addTransaction(userId, "EXPENSE", "food", "88.50", "2026-05-27");

        Transaction updated = transactionService.update(
                transaction.getId(),
                userId,
                new BigDecimal("99.90"),
                "EXPENSE",
                "transport",
                "taxi",
                LocalDate.parse("2026-05-28")
        );

        assertThat(updated.getAmount()).isEqualByComparingTo("99.90");
        assertThat(updated.getCategory()).isEqualTo("transport");
        assertThat(transactionMapper.selectById(transaction.getId()).getDescription()).isEqualTo("taxi");
    }

    @Test
    void delete_shouldSoftDeleteTransaction() {
        Long userId = createUser("alice");
        Transaction transaction = addTransaction(userId, "EXPENSE", "food", "88.50", "2026-05-27");

        transactionService.delete(transaction.getId(), userId);

        assertThat(transactionMapper.selectById(transaction.getId())).isNull();
    }

    @Test
    void getById_whenTransactionBelongsToAnotherUser_shouldThrow() {
        Long ownerId = createUser("alice");
        Long otherUserId = createUser("bob");
        Transaction transaction = addTransaction(ownerId, "EXPENSE", "food", "88.50", "2026-05-27");

        assertThatThrownBy(() -> transactionService.getById(transaction.getId(), otherUserId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listByUser_shouldApplyTypeCategoryAndDateFilters() {
        Long userId = createUser("alice");
        addTransaction(userId, "EXPENSE", "food", "88.50", "2026-05-27");
        addTransaction(userId, "EXPENSE", "transport", "20.00", "2026-05-28");
        addTransaction(userId, "INCOME", "salary", "1000.00", "2026-05-29");

        IPage<Transaction> page = transactionService.listByUser(
                userId,
                1,
                10,
                "EXPENSE",
                "food",
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-31")
        );

        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().get(0).getCategory()).isEqualTo("food");
    }

    @Test
    void getCategorySummary_shouldAggregateExpenseByCategory() {
        Long userId = createUser("alice");
        addTransaction(userId, "EXPENSE", "food", "88.50", "2026-05-27");
        addTransaction(userId, "EXPENSE", "food", "11.50", "2026-05-28");
        addTransaction(userId, "EXPENSE", "transport", "20.00", "2026-05-29");
        addTransaction(userId, "INCOME", "salary", "1000.00", "2026-05-30");

        List<Map<String, Object>> summary = transactionService.getCategorySummary(
                userId,
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-31")
        );

        assertThat(summary).hasSize(2);
        assertThat(summary.get(0).get("category")).isEqualTo("food");
        assertThat((BigDecimal) summary.get(0).get("total")).isEqualByComparingTo("100.00");
    }

    private Transaction addTransaction(Long userId, String type, String category, String amount, String transactionDate) {
        return transactionService.add(
                userId,
                new BigDecimal(amount),
                type,
                category,
                "test transaction",
                LocalDate.parse(transactionDate)
        );
    }

    private Long createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("hashed-password");
        user.setNickname(username);
        userMapper.insert(user);
        return user.getId();
    }
}
