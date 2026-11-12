package com.synera.api.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmationReminder {
    private final JdbcTemplate jdbc;

    public OrderConfirmationReminder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${synera.orders.reminder.scan-interval-ms:3600000}")
    public void notifyOverdueConfirmations() {
        jdbc.update("""
                WITH notices AS (
                    INSERT INTO order_confirmation_notice (
                        order_so_id, tenant_id, recipient_id, notice_level
                    )
                    SELECT os.id, os.tenant_id, u.id, 'REMINDER'
                    FROM order_so os
                    JOIN user_role ur ON ur.tenant_id = os.npp_tenant_id
                        AND ur.role_code IN ('NPP_OWNER', 'NPP_STAFF')
                    JOIN app_users u ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id
                        AND u.status = 'ACTIVE'
                    WHERE os.status = 'SUBMITTED' AND os.deleted_at IS NULL
                      AND os.confirmation_due_at <= CURRENT_TIMESTAMP
                    ON CONFLICT DO NOTHING
                    RETURNING order_so_id, tenant_id, recipient_id, notice_level
                )
                INSERT INTO notification (
                    id, tenant_id, recipient_id, notification_type, payload
                )
                SELECT gen_random_uuid(), tenant_id, recipient_id, 'ORDER_CONFIRMATION_REMINDER',
                    jsonb_build_object('orderId', order_so_id::text, 'level', notice_level)
                FROM notices
                """);

        jdbc.update("""
                WITH notices AS (
                    INSERT INTO order_confirmation_notice (
                        order_so_id, tenant_id, recipient_id, notice_level
                    )
                    SELECT os.id, os.tenant_id, u.id, 'ESCALATION'
                    FROM order_so os
                    JOIN user_role ur ON ur.tenant_id = os.npp_tenant_id
                        AND ur.role_code = 'NPP_OWNER'
                    JOIN app_users u ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id
                        AND u.status = 'ACTIVE'
                    WHERE os.status = 'SUBMITTED' AND os.deleted_at IS NULL
                      AND os.confirmation_due_at <= CURRENT_TIMESTAMP
                      AND os.submitted_at <= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                    ON CONFLICT DO NOTHING
                    RETURNING order_so_id, tenant_id, recipient_id, notice_level
                )
                INSERT INTO notification (
                    id, tenant_id, recipient_id, notification_type, payload
                )
                SELECT gen_random_uuid(), tenant_id, recipient_id, 'ORDER_CONFIRMATION_ESCALATION',
                    jsonb_build_object('orderId', order_so_id::text, 'level', notice_level)
                FROM notices
                """);
    }
}
