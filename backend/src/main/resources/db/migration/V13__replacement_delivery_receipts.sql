CREATE TABLE replacement_delivery (
    return_request_id UUID PRIMARY KEY REFERENCES return_request(id),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','SHIPPED','RECEIVED')),
    shipped_by UUID REFERENCES app_users(id), received_by UUID REFERENCES app_users(id),
    shipped_at TIMESTAMPTZ, received_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(received_by IS NULL OR received_by<>shipped_by)
);
ALTER TABLE task ADD COLUMN order_so_id UUID REFERENCES order_so(id);
CREATE INDEX task_order_so ON task(order_so_id) WHERE order_so_id IS NOT NULL;
ALTER TABLE ticket ADD COLUMN receipt_issue BOOLEAN NOT NULL DEFAULT false;
INSERT INTO order_status_transition(flow_type,from_status,to_status,action,required_permission,actor,notes)
VALUES('SELL_OUT','RECEIVED_ISSUE','RECEIVED_FULL','REPLACEMENT_RECEIVED','SYSTEM','SYSTEM','Only after all approved replacements were received and confirmed quantities are satisfied.')
ON CONFLICT DO NOTHING;
