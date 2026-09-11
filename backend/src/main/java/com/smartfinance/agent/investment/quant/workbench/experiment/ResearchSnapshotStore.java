package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Component
public class ResearchSnapshotStore {
    public static final String FORMAT_VERSION = "research-snapshot-v1";
    private static final String COMPRESSION = "GZIP";

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final long maxUncompressedBytes;
    private final long maxCompressedBytes;

    public ResearchSnapshotStore(
            JdbcTemplate db,
            ObjectMapper json,
            @Value("${quant.workbench.snapshot.max-uncompressed-bytes:134217728}") long maxUncompressedBytes,
            @Value("${quant.workbench.snapshot.max-compressed-bytes:33554432}") long maxCompressedBytes) {
        this.db = db;
        this.json = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.maxUncompressedBytes = maxUncompressedBytes;
        this.maxCompressedBytes = maxCompressedBytes;
    }

    public ExperimentModels.SnapshotView put(
            Long userId, List<Map<String, Object>> assets, Map<String, Object> metadata) {
        byte[] plain = write(assets);
        requireSize(plain.length, maxUncompressedBytes);
        String hash = sha256(plain);
        var existing = findByContent(userId, hash);
        if (existing != null) return existing;

        byte[] compressed = gzip(plain);
        requireSize(compressed.length, maxCompressedBytes);
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();
        try {
            db.update("INSERT INTO quant_v2_research_snapshot(" +
                            "id,user_id,format_version,content_hash,compression,payload_blob,metadata_json," +
                            "uncompressed_bytes,compressed_bytes,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, userId, FORMAT_VERSION, hash, COMPRESSION, compressed, text(metadata),
                    plain.length, compressed.length, createdAt);
        } catch (DuplicateKeyException concurrentInsert) {
            var winner = findByContent(userId, hash);
            if (winner != null) return winner;
            throw concurrentInsert;
        }
        return new ExperimentModels.SnapshotView(id, userId, FORMAT_VERSION, hash, COMPRESSION,
                metadata, plain.length, compressed.length, createdAt);
    }

    public List<Map<String, Object>> loadAssets(Long userId, String snapshotId) {
        var rows = db.queryForList("SELECT content_hash,compression,payload_blob,uncompressed_bytes," +
                "compressed_bytes FROM quant_v2_research_snapshot WHERE id=? AND user_id=?", snapshotId, userId);
        if (rows.isEmpty()) throw new SnapshotException("SNAPSHOT_NOT_FOUND", "研究数据快照不存在或无权访问");
        var row = rows.get(0);
        try {
            byte[] compressed = (byte[]) row.get("payload_blob");
            if (!COMPRESSION.equals(row.get("compression"))
                    || compressed.length != ((Number) row.get("compressed_bytes")).longValue()) {
                throw new IllegalStateException("Snapshot metadata mismatch");
            }
            byte[] plain = gunzip(compressed);
            if (plain.length != ((Number) row.get("uncompressed_bytes")).longValue()
                    || !sha256(plain).equals(row.get("content_hash"))) {
                throw new IllegalStateException("Snapshot hash mismatch");
            }
            return json.readValue(plain, new TypeReference<List<Map<String, Object>>>() {});
        } catch (SnapshotException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SnapshotException("SNAPSHOT_CORRUPTED", "研究数据快照校验失败", exception);
        }
    }

    public Map<String, Object> metadata(Long userId, String snapshotId) {
        var rows = db.queryForList("SELECT metadata_json FROM quant_v2_research_snapshot " +
                "WHERE id=? AND user_id=?", snapshotId, userId);
        if (rows.isEmpty()) throw new SnapshotException("SNAPSHOT_NOT_FOUND", "研究数据快照不存在或无权访问");
        try {
            return json.readValue(String.valueOf(rows.get(0).get("metadata_json")),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new SnapshotException("SNAPSHOT_CORRUPTED", "研究数据快照元数据损坏", exception);
        }
    }

    private ExperimentModels.SnapshotView findByContent(Long userId, String hash) {
        var rows = db.queryForList("SELECT id,user_id,format_version,content_hash,compression,metadata_json," +
                        "uncompressed_bytes,compressed_bytes,created_at FROM quant_v2_research_snapshot " +
                        "WHERE user_id=? AND format_version=? AND content_hash=?",
                userId, FORMAT_VERSION, hash);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        try {
            return new ExperimentModels.SnapshotView(
                    String.valueOf(row.get("id")), ((Number) row.get("user_id")).longValue(),
                    String.valueOf(row.get("format_version")), String.valueOf(row.get("content_hash")),
                    String.valueOf(row.get("compression")),
                    json.readValue(String.valueOf(row.get("metadata_json")), new TypeReference<Map<String, Object>>() {}),
                    ((Number) row.get("uncompressed_bytes")).longValue(),
                    ((Number) row.get("compressed_bytes")).longValue(), String.valueOf(row.get("created_at")));
        } catch (Exception exception) {
            throw new SnapshotException("SNAPSHOT_CORRUPTED", "研究数据快照元数据损坏", exception);
        }
    }

    private byte[] write(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new SnapshotException("SNAPSHOT_INVALID", "研究数据快照无法序列化", exception);
        }
    }

    private String text(Object value) {
        return new String(write(value), StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] plain) {
        try {
            var output = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(output)) {
                gzip.write(plain);
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new SnapshotException("SNAPSHOT_INVALID", "研究数据快照无法压缩", exception);
        }
    }

    private byte[] gunzip(byte[] compressed) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = gzip.read(buffer)) >= 0) {
                total += read;
                if (total > maxUncompressedBytes) {
                    throw new SnapshotException("SNAPSHOT_TOO_LARGE", "研究数据快照超过解压限制");
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static void requireSize(long actual, long maximum) {
        if (actual > maximum) throw new SnapshotException("SNAPSHOT_TOO_LARGE", "研究数据快照超过大小限制");
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static final class SnapshotException extends RuntimeException {
        private final String code;

        SnapshotException(String code, String message) {
            super(message);
            this.code = code;
        }

        SnapshotException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
