ALTER TABLE order_si
    ADD COLUMN IF NOT EXISTS request_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS subtotal_vnd BIGINT NOT NULL DEFAULT 0 CHECK (subtotal_vnd >= 0),
    ADD COLUMN IF NOT EXISTS discount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (discount_vnd >= 0),
    ADD COLUMN IF NOT EXISTS total_vnd BIGINT NOT NULL DEFAULT 0 CHECK (total_vnd >= 0),
    ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expected_delivery_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS received_at TIMESTAMPTZ;

ALTER TABLE order_line
    ADD COLUMN IF NOT EXISTS entered_qty BIGINT NOT NULL DEFAULT 0 CHECK (entered_qty >= 0),
    ADD COLUMN IF NOT EXISTS gross_amount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (gross_amount_vnd >= 0),
    ADD COLUMN IF NOT EXISTS discount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (discount_vnd >= 0),
    ADD COLUMN IF NOT EXISTS line_amount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (line_amount_vnd >= 0);

ALTER TABLE invoice
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS order_si_receipt_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    order_si_id UUID NOT NULL REFERENCES order_si(id),
    order_line_id UUID NOT NULL REFERENCES order_line(id),
    received_qty BIGINT NOT NULL CHECK (received_qty >= 0),
    damaged_qty BIGINT NOT NULL DEFAULT 0 CHECK (damaged_qty >= 0),
    note TEXT NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (damaged_qty <= received_qty),
    UNIQUE (order_si_id, order_line_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS invoice_order_si_unique_idx
    ON invoice (tenant_id, order_si_id)
    WHERE order_si_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS credit_limit_tenant_npp_status_idx
    ON credit_limit (tenant_id, npp_tenant_id, status, effective_from DESC);
CREATE INDEX IF NOT EXISTS invoice_tenant_npp_status_idx
    ON invoice (tenant_id, npp_tenant_id, status, issued_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS payment_tenant_npp_status_idx
    ON payment (tenant_id, npp_tenant_id, status, created_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS order_si_receipt_tenant_order_idx
    ON order_si_receipt_line (tenant_id, order_si_id);
