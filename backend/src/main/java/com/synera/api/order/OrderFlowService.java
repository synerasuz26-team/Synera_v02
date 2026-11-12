package com.synera.api.order;

import com.synera.api.auth.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class OrderFlowService {
    private final JdbcTemplate jdbc;

    public OrderFlowService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long transition(
            String flowType, UUID tenantId, UUID orderId, String fromStatus, String toStatus,
            String permission, UUID actorId, String reason, long expectedVersion) {
        boolean system = "SYSTEM".equals(permission);
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM order_status_transition
                    WHERE flow_type = ? AND from_status = ? AND to_status = ?
                      AND ((? = TRUE AND actor = 'SYSTEM' AND required_permission = 'SYSTEM')
                        OR (? = FALSE AND required_permission = ?))
                )
                """, Boolean.class, flowType, fromStatus, toStatus, system, system, permission);
        if (!Boolean.TRUE.equals(allowed)) {
            throw invalidTransition(flowType, fromStatus, toStatus);
        }

        String table;
        String idColumn;
        if ("SELL_OUT".equals(flowType)) {
            table = "order_so";
            idColumn = "order_so_id";
        } else if ("SELL_IN".equals(flowType)) {
            table = "order_si";
            idColumn = "order_si_id";
        } else {
            throw new IllegalArgumentException("Không hỗ trợ luồng đơn hàng.");
        }
        String updateSql = """
                UPDATE %s SET status = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE tenant_id = ? AND id = ? AND status = ? AND version = ? AND deleted_at IS NULL
                """.formatted(table);
        int updated = jdbc.update(updateSql, toStatus, tenantId, orderId, fromStatus, expectedVersion);
        if (updated == 0) {
            throw new ApiProblem("ORDER_VERSION_CONFLICT", "Đơn hàng đã được cập nhật hoặc chuyển trạng thái.",
                    HttpStatus.CONFLICT, Map.of("orderId", orderId.toString()));
        }
        jdbc.update("""
                INSERT INTO order_status_history (
                    id, tenant_id, order_si_id, order_so_id, from_status, to_status, actor_id, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId,
                "SELL_IN".equals(flowType) ? orderId : null,
                "SELL_OUT".equals(flowType) ? orderId : null,
                fromStatus, toStatus, actorId, reason == null ? "" : reason);
        return expectedVersion + 1;
    }

    public void requireTransition(String flowType, String fromStatus, String toStatus, String permission) {
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM order_status_transition
                    WHERE flow_type = ? AND from_status = ? AND to_status = ?
                      AND required_permission = ?
                )
                """, Boolean.class, flowType, fromStatus, toStatus, permission);
        if (!Boolean.TRUE.equals(allowed)) {
            throw invalidTransition(flowType, fromStatus, toStatus);
        }
    }

    private ApiProblem invalidTransition(String flowType, String fromStatus, String toStatus) {
        return new ApiProblem("INVALID_ORDER_TRANSITION", "Không thể chuyển trạng thái đơn theo quy trình.",
                HttpStatus.CONFLICT, Map.of(
                "flowType", flowType, "fromStatus", fromStatus, "toStatus", toStatus));
    }
}
