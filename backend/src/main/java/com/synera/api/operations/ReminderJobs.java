package com.synera.api.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class ReminderJobs {
    private final JdbcTemplate jdbc;
    public ReminderJobs(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public long scan(UUID tenant,RuntimeSettings.Values config) {
        long count=0;
        count+=jdbc.update("""
            INSERT INTO notification(id,tenant_id,recipient_id,notification_type,event_key,payload)
            SELECT gen_random_uuid(),t.tenant_id,u.id,'TASK_DUE','task-due:'||t.id||':'||t.due_at,
                jsonb_build_object('taskId',t.id,'title',t.title,'dueAt',t.due_at)
            FROM task t JOIN app_users u ON u.id=COALESCE(t.assignee_id,t.created_by) AND u.tenant_id=t.tenant_id AND u.status='ACTIVE'
            WHERE t.tenant_id=? AND t.deleted_at IS NULL AND t.status IN('OPEN','IN_PROGRESS')
              AND t.due_at<=CURRENT_TIMESTAMP+(? * INTERVAL '1 minute') ON CONFLICT DO NOTHING
            """,tenant,config.number("reminder_lead_minutes"));
        count+=jdbc.update("""
            INSERT INTO notification(id,tenant_id,recipient_id,notification_type,event_key,payload)
            SELECT gen_random_uuid(),i.tenant_id,u.id,'APPOINTMENT_DUE','appointment:'||i.id||':'||i.follow_up_at,
                jsonb_build_object('interactionId',i.id,'dueAt',i.follow_up_at)
            FROM interaction i JOIN app_users u ON u.id=i.actor_id AND u.tenant_id=i.tenant_id AND u.status='ACTIVE'
            WHERE i.tenant_id=? AND i.follow_up_at<=CURRENT_TIMESTAMP+(? * INTERVAL '1 minute') ON CONFLICT DO NOTHING
            """,tenant,config.number("reminder_lead_minutes"));
        count+=jdbc.update("""
            INSERT INTO notification(id,tenant_id,recipient_id,notification_type,event_key,payload)
            SELECT gen_random_uuid(),t.tenant_id,u.id,'TICKET_SLA_WARNING','ticket-sla:'||t.id||':'||COALESCE(t.due_at,t.created_at+(? * INTERVAL '1 minute')),
                jsonb_build_object('ticketId',t.id,'title',t.title)
            FROM ticket t JOIN app_users u ON u.id=COALESCE(t.assignee_id,t.created_by) AND u.tenant_id=t.tenant_id AND u.status='ACTIVE'
            WHERE t.tenant_id=? AND t.deleted_at IS NULL AND t.status NOT IN('CLOSED','RESOLVED','CANCELLED')
              AND COALESCE(t.due_at,t.created_at+(? * INTERVAL '1 minute'))<=CURRENT_TIMESTAMP+(? * INTERVAL '1 minute')
            ON CONFLICT DO NOTHING
            """,config.number("ticket_sla_minutes"),tenant,config.number("ticket_sla_minutes"),config.number("ticket_warning_minutes"));
        count+=receiptReminders(tenant,"REMINDER",false,config.number("escalation_minutes"),config.number("delivery_sla_minutes"));
        count+=receiptReminders(tenant,"ESCALATION",true,config.number("escalation_minutes"),config.number("delivery_sla_minutes"));
        count+=jdbc.update("""
            INSERT INTO notification(id,tenant_id,recipient_id,notification_type,event_key,payload)
            SELECT gen_random_uuid(),u.tenant_id,u.id,'NPP_ORDER_CYCLE','npp-cycle:'||r.nsx_tenant_id||':'||r.npp_tenant_id||':'||COALESCE(last_order.id::text,'none')||':'||floor(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-COALESCE(last_order.created_at,r.created_at)))/(? * 86400))::bigint,
                jsonb_build_object('nsxTenantId',r.nsx_tenant_id,'nppTenantId',r.npp_tenant_id)
            FROM tenant_relation r JOIN app_users u ON u.tenant_id=r.npp_tenant_id AND u.status='ACTIVE'
            LEFT JOIN LATERAL(SELECT id,created_at FROM order_si o WHERE o.tenant_id=r.nsx_tenant_id AND o.npp_tenant_id=r.npp_tenant_id AND o.deleted_at IS NULL AND o.status NOT IN('DRAFT','CANCELLED','REJECTED') ORDER BY created_at DESC LIMIT 1) last_order ON true
            WHERE r.nsx_tenant_id=? AND r.status='ACTIVE' AND r.effective_from<=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AND (r.effective_to IS NULL OR r.effective_to>=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
              AND COALESCE(last_order.created_at,r.created_at)<=CURRENT_TIMESTAMP-(? * INTERVAL '1 day')
              AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.tenant_id=u.tenant_id AND ur.role_code='NPP_OWNER')
            ON CONFLICT DO NOTHING
            """,config.number("npp_cycle_days"),tenant,config.number("npp_cycle_days"));
        return count;
    }
    private int receiptReminders(UUID tenant,String level,boolean escalate,long delay,long deliveryMinutes) {
        // Two independent events: the escalation needs an older reminder, never an auto-confirmation.
        int count=0;
        for(String table:java.util.List.of("order_so","order_si")) {
            String fallback="o.updated_at+INTERVAL '"+deliveryMinutes+" minutes'";
            String source=table.equals("order_so")?"(CASE WHEN o.status='SUBMITTED' THEN o.confirmation_due_at ELSE COALESCE(o.expected_delivery_at,"+fallback+") END)":"COALESCE(o.expected_delivery_at,"+fallback+")";
            String statuses=table.equals("order_so")?"('SUBMITTED','SHIPPED','PARTIALLY_RECEIVED')":"('SHIPPED')";
            count+=jdbc.update("""
                INSERT INTO notification(id,tenant_id,recipient_id,notification_type,event_key,payload)
                SELECT gen_random_uuid(),u.tenant_id,u.id,?,?||o.id||':'||?||':'||%s,
                    jsonb_build_object('orderId',o.id,'flow',?,'level',?)
                FROM %s o JOIN app_users u ON u.tenant_id=o.npp_tenant_id AND u.status='ACTIVE'
                WHERE o.tenant_id=? AND o.deleted_at IS NULL AND o.status IN %s AND %s<=CURRENT_TIMESTAMP
                  AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.tenant_id=u.tenant_id AND ur.role_code IN %s)
                  AND (?=false OR EXISTS(SELECT 1 FROM notification n WHERE n.recipient_id=u.id AND n.event_key=?||o.id||':REMINDER:'||%s AND n.created_at<=CURRENT_TIMESTAMP-(? * INTERVAL '1 minute')))
                ON CONFLICT DO NOTHING
                """.formatted(source,table,statuses,source,escalate?"('NPP_OWNER')":"('NPP_OWNER','NPP_STAFF')",source),
                "ORDER_CONFIRMATION_"+level,"receipt:"+table+":",level,table.equals("order_so")?"SELL_OUT":"SELL_IN",level,tenant,escalate,"receipt:"+table+":",delay);
        }
        return count;
    }
}
