CREATE TABLE IF NOT EXISTS data_import_batch (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    npp_tenant_id UUID NOT NULL REFERENCES tenant(id),
    import_type VARCHAR(16) NOT NULL CHECK (import_type IN ('INVENTORY', 'SELLOUT')),
    file_sha256 CHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PREVIEWED'
        CHECK (status IN ('PREVIEWED', 'COMMITTED')),
    row_count INTEGER NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    valid_count INTEGER NOT NULL DEFAULT 0 CHECK (valid_count >= 0),
    invalid_count INTEGER NOT NULL DEFAULT 0 CHECK (invalid_count >= 0),
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id), 
    UNIQUE (tenant_id, import_type, file_sha256),
    CHECK (tenant_id = npp_tenant_id)
);

CREATE TABLE IF NOT EXISTS data_import_row (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    batch_id UUID NOT NULL,
    line_number INTEGER NOT NULL CHECK (line_number > 0),
    row_data JSONB NOT NULL,
    errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    valid BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (batch_id, line_number),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES data_import_batch (tenant_id, id),
    CHECK (jsonb_typeof(errors) = 'array')
);

CREATE INDEX IF NOT EXISTS data_import_batch_tenant_status_created_idx
    ON data_import_batch (tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS data_import_row_tenant_batch_idx
    ON data_import_row (tenant_id, batch_id, line_number);
CREATE INDEX IF NOT EXISTS demand_plan_tenant_npp_planned_idx
    ON demand_plan (tenant_id, npp_tenant_id, planned_for DESC);
CREATE INDEX IF NOT EXISTS demand_plan_tenant_status_updated_idx
    ON demand_plan (tenant_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS target_tenant_period_subject_idx
    ON target (tenant_id, period_id, subject_id);
CREATE INDEX IF NOT EXISTS target_tenant_updated_idx
    ON target (tenant_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS inventory_npp_tenant_npp_recorded_idx
    ON inventory_npp (tenant_id, npp_tenant_id, recorded_at DESC);
CREATE INDEX IF NOT EXISTS sellout_tenant_product_date_idx
    ON sellout_record (tenant_id, product_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS sellout_tenant_npp_date_idx
    ON sellout_record (tenant_id, npp_tenant_id, occurred_at DESC);
