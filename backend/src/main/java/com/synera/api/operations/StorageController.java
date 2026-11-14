package com.synera.api.operations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import java.util.List;

@RestController
@ConditionalOnProperty(name = "app.operations.enabled", havingValue = "true")
@SecurityRequirement(name = "bearerAuth")
public class StorageController {
    private static final long VOLUME_CAPACITY_BYTES = 500_000_000L;
    private static final long DATABASE_BUDGET_BYTES = 300_000_000L;

    private final ActiveIdentity identity;
    private final JdbcTemplate jdbc;

    public StorageController(ActiveIdentity identity, JdbcTemplate jdbc) {
        this.identity = identity;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/admin/storage")
    public StorageReport storage(@AuthenticationPrincipal Jwt jwt) {
        identity.systemAdmin(jwt);
        long databaseBytes = jdbc.queryForObject(
                "SELECT pg_database_size(current_database())", Long.class);
        List<TableUsage> tables = jdbc.query("""
                SELECT c.relname AS table_name,
                    pg_relation_size(c.oid) AS data_bytes,
                    pg_indexes_size(c.oid) AS index_bytes,
                    pg_total_relation_size(c.oid) AS total_bytes,
                    COALESCE(s.n_live_tup, 0) AS estimated_rows
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
                WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'm')
                ORDER BY pg_total_relation_size(c.oid) DESC, c.relname
                """, (rs, row) -> new TableUsage(
                rs.getString("table_name"), rs.getLong("estimated_rows"),
                rs.getLong("data_bytes"), rs.getLong("index_bytes"),
                rs.getLong("total_bytes"), percent(rs.getLong("total_bytes"), VOLUME_CAPACITY_BYTES)));
        int volumePercent = percentage(databaseBytes, VOLUME_CAPACITY_BYTES);
        String status = databaseBytes > VOLUME_CAPACITY_BYTES * 85 / 100 ? "CRITICAL"
                : databaseBytes > VOLUME_CAPACITY_BYTES * 70 / 100 ? "WARNING" : "HEALTHY";
        if (!"HEALTHY".equals(status)) {
            LoggerFactory.getLogger(getClass()).warn(
                    "database capacity estimate status={} database_bytes={} estimated_percent={}",
                    status, databaseBytes, volumePercent);
        }
        return new StorageReport(
                databaseBytes, VOLUME_CAPACITY_BYTES, DATABASE_BUDGET_BYTES,
                volumePercent, percentage(databaseBytes, DATABASE_BUDGET_BYTES),
                status, false,
                "Đây là dung lượng database; PostgreSQL không tính WAL và tệp khác của volume vào số này. "
                        + "Đối chiếu Railway Volume usage trước khi deploy hoặc migration.",
                tables);
    }

    private static int percentage(long value, long denominator) {
        return denominator == 0 ? 0 : (int) Math.min(100, Math.round(value * 100.0 / denominator));
    }

    private static double percent(long value, long denominator) {
        return denominator == 0 ? 0 : Math.round(value * 10000.0 / denominator) / 100.0;
    }

    public record StorageReport(
            long databaseBytes, long volumeCapacityBytes, long databaseBudgetBytes,
            int volumePercent, int budgetPercent, String status, boolean volumeUsageMeasured,
            String note, List<TableUsage> tables) {
    }

    public record TableUsage(
            String table, long estimatedRows, long dataBytes, long indexBytes,
            long totalBytes, double volumePercent) {
    }
}
