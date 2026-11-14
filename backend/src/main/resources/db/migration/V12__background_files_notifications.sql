CREATE TABLE runtime_config_defaults (
    config_key VARCHAR(120) PRIMARY KEY,
    config_value JSONB NOT NULL
);
INSERT INTO runtime_config_defaults VALUES ('p6.runtime', '{
 "nightly_enabled":true,"nightly_hour":1,"nightly_minute":10,
 "reminders_enabled":true,"reminder_lead_minutes":60,"escalation_minutes":1440,
 "ticket_sla_minutes":1440,"ticket_warning_minutes":120,"npp_cycle_days":7,
 "confirmation_minutes":240,"delivery_sla_minutes":1440,
 "variance_warning_percent":5,"approval_threshold_vnd":0,
 "upload_max_bytes":5242880,"upload_expiry_minutes":10,
 "image_max_edge":2048,"thumbnail_edge":256,"token_retention_days":7,
 "push_enabled":false,"push_max_attempts":5
}'::jsonb);
INSERT INTO tenant_config(id,tenant_id,config_key,config_value)
 SELECT gen_random_uuid(),t.id,d.config_key,d.config_value FROM tenant t CROSS JOIN runtime_config_defaults d
 ON CONFLICT(tenant_id,config_key) DO NOTHING;
CREATE FUNCTION initialize_runtime_config() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO tenant_config(id,tenant_id,config_key,config_value)
 SELECT gen_random_uuid(),NEW.id,config_key,config_value FROM runtime_config_defaults
 ON CONFLICT(tenant_id,config_key) DO NOTHING;
 RETURN NEW;
END $$;
CREATE TRIGGER tenant_runtime_config AFTER INSERT ON tenant FOR EACH ROW EXECUTE FUNCTION initialize_runtime_config();

CREATE TABLE background_job_run (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant(id), job_name VARCHAR(60) NOT NULL,
 run_key VARCHAR(100) NOT NULL, status VARCHAR(20) NOT NULL CHECK(status IN('RUNNING','SUCCEEDED','FAILED','SKIPPED','ABANDONED')),
 started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, finished_at TIMESTAMPTZ,
 record_count BIGINT NOT NULL DEFAULT 0, details JSONB NOT NULL DEFAULT '{}', error_code VARCHAR(160)
);
CREATE UNIQUE INDEX background_job_success_once ON background_job_run(tenant_id,job_name,run_key) WHERE status='SUCCEEDED';
CREATE INDEX background_job_history ON background_job_run(tenant_id,started_at DESC);

ALTER TABLE notification ADD COLUMN event_key TEXT;
-- Recipient owns the inbox, even when the source order is owned by another tenant.
UPDATE notification n SET tenant_id=u.tenant_id FROM app_users u WHERE u.id=n.recipient_id AND n.tenant_id<>u.tenant_id;
CREATE UNIQUE INDEX notification_event_once ON notification(recipient_id,event_key) WHERE event_key IS NOT NULL;
CREATE INDEX notification_inbox ON notification(tenant_id,recipient_id,created_at DESC,id);
CREATE TABLE push_device (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL REFERENCES tenant(id),user_id UUID NOT NULL REFERENCES app_users(id),
 device_id VARCHAR(160) NOT NULL,expo_token VARCHAR(240) NOT NULL UNIQUE,active BOOLEAN NOT NULL DEFAULT true,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,device_id)
);
CREATE TABLE push_delivery (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL REFERENCES tenant(id),notification_id UUID NOT NULL REFERENCES notification(id),
 device_id UUID NOT NULL REFERENCES push_device(id), status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 ticket_id VARCHAR(200),last_error VARCHAR(120),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(notification_id,device_id)
);
CREATE INDEX push_delivery_due ON push_delivery(tenant_id,status,next_attempt_at);
CREATE FUNCTION notification_recipient_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 SELECT tenant_id INTO STRICT NEW.tenant_id FROM app_users WHERE id=NEW.recipient_id;
 RETURN NEW;
END $$;
CREATE TRIGGER notification_owner BEFORE INSERT ON notification FOR EACH ROW EXECUTE FUNCTION notification_recipient_owner();
CREATE FUNCTION enqueue_notification_push() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO push_delivery(id,tenant_id,notification_id,device_id)
 SELECT gen_random_uuid(),NEW.tenant_id,NEW.id,d.id FROM push_device d
 WHERE d.user_id=NEW.recipient_id AND d.tenant_id=NEW.tenant_id AND d.active;
 RETURN NEW;
END $$;
CREATE TRIGGER notification_push AFTER INSERT ON notification FOR EACH ROW EXECUTE FUNCTION enqueue_notification_push();

CREATE FUNCTION notify_order_status_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_creator UUID; partner UUID; source_amount BIGINT; approval_threshold BIGINT; needs_approval BOOLEAN := false;
BEGIN
 IF NEW.order_si_id IS NOT NULL THEN
   SELECT created_by,npp_tenant_id,total_vnd INTO source_creator,partner,source_amount FROM order_si WHERE id=NEW.order_si_id;
   SELECT (config_value->>'approval_threshold_vnd')::bigint INTO approval_threshold FROM tenant_config WHERE tenant_id=NEW.tenant_id AND config_key='p6.runtime';
   needs_approval := NEW.to_status='PENDING_APPROVAL' AND source_amount>=approval_threshold;
 ELSE
   SELECT created_by,npp_tenant_id INTO source_creator,partner FROM order_so WHERE id=NEW.order_so_id;
 END IF;
 INSERT INTO notification(id,tenant_id,recipient_id,notification_type,event_key,payload)
 SELECT gen_random_uuid(),u.tenant_id,u.id,'ORDER_STATUS_CHANGED','order-status:'||NEW.id,
 jsonb_build_object('orderId',COALESCE(NEW.order_si_id,NEW.order_so_id),'flow',CASE WHEN NEW.order_si_id IS NOT NULL THEN 'SELL_IN' ELSE 'SELL_OUT' END,'status',NEW.to_status,'approvalRequired',needs_approval)
 FROM app_users u WHERE u.status='ACTIVE' AND (u.id=source_creator OR (u.tenant_id=partner AND EXISTS(
   SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.tenant_id=u.tenant_id AND ur.role_code IN('NPP_OWNER','NPP_STAFF')))
   OR (needs_approval AND u.tenant_id=NEW.tenant_id AND EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code WHERE ur.user_id=u.id AND ur.tenant_id=u.tenant_id AND rp.permission_code='order_si:approve' AND rp.scope_code='A')))
 ON CONFLICT DO NOTHING;
 RETURN NEW;
END $$;
CREATE TRIGGER order_status_notification AFTER INSERT ON order_status_history FOR EACH ROW EXECUTE FUNCTION notify_order_status_change();
CREATE FUNCTION notify_task_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.assignee_id IS NOT NULL AND (TG_OP='INSERT' OR NEW.assignee_id IS DISTINCT FROM OLD.assignee_id) THEN
 INSERT INTO notification(id,tenant_id,recipient_id,notification_type,event_key,payload)
 SELECT gen_random_uuid(),u.tenant_id,u.id,'TASK_ASSIGNED','task:'||NEW.id||':'||NEW.version,
 jsonb_build_object('taskId',NEW.id,'title',NEW.title) FROM app_users u
 WHERE u.id=NEW.assignee_id AND u.tenant_id=NEW.tenant_id AND u.status='ACTIVE' ON CONFLICT DO NOTHING;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER task_assignment_notification AFTER INSERT OR UPDATE OF assignee_id ON task FOR EACH ROW EXECUTE FUNCTION notify_task_assignment();

ALTER TABLE attachment ADD COLUMN upload_status VARCHAR(20) NOT NULL DEFAULT 'LEGACY',
 ADD COLUMN upload_key TEXT, ADD COLUMN thumbnail_key TEXT, ADD COLUMN expires_at TIMESTAMPTZ,
 ADD COLUMN completed_at TIMESTAMPTZ;
ALTER TABLE attachment ADD CONSTRAINT attachment_upload_status CHECK(upload_status IN('LEGACY','PENDING','READY','EXPIRED','REJECTED'));
CREATE INDEX attachment_owner ON attachment(tenant_id,owner_type,owner_id,upload_status);
CREATE INDEX attachment_expiry ON attachment(expires_at) WHERE upload_status='PENDING';
ALTER TABLE interaction ADD COLUMN follow_up_at TIMESTAMPTZ;
CREATE INDEX interaction_follow_up ON interaction(tenant_id,follow_up_at) WHERE follow_up_at IS NOT NULL;
