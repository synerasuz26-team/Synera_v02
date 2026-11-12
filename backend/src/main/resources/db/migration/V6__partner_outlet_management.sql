ALTER TABLE outlet
    ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES app_users(id),
    ADD COLUMN IF NOT EXISTS created_by_tenant_id UUID REFERENCES tenant(id),
    ADD COLUMN IF NOT EXISTS approved_by UUID REFERENCES app_users(id),
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS outlet_approval_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    outlet_id UUID NOT NULL REFERENCES outlet(id),
    npp_tenant_id UUID REFERENCES tenant(id),
    route_id UUID REFERENCES route(id),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    actor_id UUID NOT NULL REFERENCES app_users(id),
    reason VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS outlet_created_by_tenant_status_idx
    ON outlet (created_by_tenant_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS outlet_approval_timeline_idx
    ON outlet_approval_history (tenant_id, outlet_id, occurred_at DESC);
