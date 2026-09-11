package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchSnapshotStoreTest {
    private JdbcTemplate db;
    private ResearchSnapshotStore store;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/sqlite/V33__quant_parameter_sensitivity_experiments.sql")).execute(source);
        store = new ResearchSnapshotStore(db, new ObjectMapper(), 128 * 1024, 64 * 1024);
    }

    @Test
    void identicalCanonicalContentSharesOneSnapshotForTheSameUser() {
        var firstBar = new LinkedHashMap<String, Object>();
        firstBar.put("date", "2026-01-02");
        firstBar.put("close", 10);
        var reorderedBar = new LinkedHashMap<String, Object>();
        reorderedBar.put("close", 10);
        reorderedBar.put("date", "2026-01-02");

        var first = store.put(7L, List.of(Map.of("id", "asset-1", "bars", List.of(firstBar))),
                Map.of("assetCount", 1));
        var second = store.put(7L, List.of(Map.of("bars", List.of(reorderedBar), "id", "asset-1")),
                Map.of("assetCount", 1));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_research_snapshot", Integer.class))
                .isEqualTo(1);
        assertThat(store.loadAssets(7L, first.id())).isEqualTo(
                List.of(Map.of("id", "asset-1", "bars", List.of(Map.of("date", "2026-01-02", "close", 10)))));
        assertThat(store.metadata(7L, first.id())).containsEntry("assetCount", 1);
    }

    @Test
    void snapshotsAreUserScopedAndContentIsVerifiedWhenRead() {
        var snapshot = store.put(7L, List.of(Map.of("id", "asset-1", "bars", List.of())), Map.of());

        assertThatThrownBy(() -> store.loadAssets(8L, snapshot.id()))
                .isInstanceOf(ResearchSnapshotStore.SnapshotException.class)
                .extracting(error -> ((ResearchSnapshotStore.SnapshotException) error).code())
                .isEqualTo("SNAPSHOT_NOT_FOUND");

        db.update("UPDATE quant_v2_research_snapshot SET payload_blob=? WHERE id=?", new byte[]{1, 2, 3}, snapshot.id());
        assertThatThrownBy(() -> store.loadAssets(7L, snapshot.id()))
                .isInstanceOf(ResearchSnapshotStore.SnapshotException.class)
                .extracting(error -> ((ResearchSnapshotStore.SnapshotException) error).code())
                .isEqualTo("SNAPSHOT_CORRUPTED");
    }

    @Test
    void oversizedSnapshotsAreRejectedBeforeInsert() {
        var limited = new ResearchSnapshotStore(db, new ObjectMapper(), 16, 16);

        assertThatThrownBy(() -> limited.put(7L,
                List.of(Map.of("id", "asset-with-payload", "bars", List.of(Map.of("close", 10)))), Map.of()))
                .isInstanceOf(ResearchSnapshotStore.SnapshotException.class)
                .extracting(error -> ((ResearchSnapshotStore.SnapshotException) error).code())
                .isEqualTo("SNAPSHOT_TOO_LARGE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM quant_v2_research_snapshot", Integer.class))
                .isZero();
    }
}
