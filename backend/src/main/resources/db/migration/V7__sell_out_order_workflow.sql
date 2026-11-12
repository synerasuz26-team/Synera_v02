ALTER TABLE order_so
    ADD COLUMN IF NOT EXISTS request_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS subtotal_vnd BIGINT NOT NULL DEFAULT 0 CHECK (subtotal_vnd >= 0),
    ADD COLUMN IF NOT EXISTS discount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (discount_vnd >= 0),
    ADD COLUMN IF NOT EXISTS total_vnd BIGINT NOT NULL DEFAULT 0 CHECK (total_vnd >= 0),
    ADD COLUMN IF NOT EXISTS note TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS voucher_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS confirmation_due_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expected_delivery_at TIMESTAMPTZ;

ALTER TABLE order_line
    ADD COLUMN IF NOT EXISTS entered_qty BIGINT NOT NULL DEFAULT 0 CHECK (entered_qty >= 0),
    ADD COLUMN IF NOT EXISTS gross_amount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (gross_amount_vnd >= 0),
    ADD COLUMN IF NOT EXISTS discount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (discount_vnd >= 0),
    ADD COLUMN IF NOT EXISTS line_amount_vnd BIGINT NOT NULL DEFAULT 0 CHECK (line_amount_vnd >= 0),
    ADD COLUMN IF NOT EXISTS promotion_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

ALTER TABLE return_request
    ADD COLUMN IF NOT EXISTS request_type VARCHAR(32) NOT NULL DEFAULT 'RETURN',
    ADD COLUMN IF NOT EXISTS resolution TEXT NOT NULL DEFAULT '';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'return_request'::regclass AND conname = 'return_request_type_check'
    ) THEN
        ALTER TABLE return_request
            ADD CONSTRAINT return_request_type_check
                CHECK (request_type IN ('RETURN', 'EXCHANGE', 'REPLACEMENT'));
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS order_receipt_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    order_so_id UUID NOT NULL REFERENCES order_so(id),
    order_line_id UUID NOT NULL REFERENCES order_line(id),
    received_qty BIGINT NOT NULL CHECK (received_qty >= 0),
    damaged_qty BIGINT NOT NULL DEFAULT 0 CHECK (damaged_qty >= 0),
    note TEXT NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (damaged_qty <= received_qty),
    UNIQUE (order_so_id, order_line_id)
);

CREATE TABLE IF NOT EXISTS order_receipt_attachment (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    order_so_id UUID NOT NULL REFERENCES order_so(id),
    attachment_id UUID NOT NULL REFERENCES attachment(id),
    PRIMARY KEY (order_so_id, attachment_id)
);

CREATE TABLE IF NOT EXISTS return_request_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    return_request_id UUID NOT NULL REFERENCES return_request(id),
    order_line_id UUID NOT NULL REFERENCES order_line(id),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    UNIQUE (return_request_id, order_line_id)
);

CREATE TABLE IF NOT EXISTS order_confirmation_notice (
    order_so_id UUID NOT NULL REFERENCES order_so(id),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    recipient_id UUID NOT NULL REFERENCES app_users(id),
    notice_level VARCHAR(16) NOT NULL CHECK (notice_level IN ('REMINDER', 'ESCALATION')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (order_so_id, recipient_id, notice_level)
);

CREATE INDEX IF NOT EXISTS order_so_confirmation_due_idx
    ON order_so (status, confirmation_due_at)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS order_receipt_tenant_order_idx
    ON order_receipt_line (tenant_id, order_so_id);
CREATE INDEX IF NOT EXISTS return_line_tenant_request_idx
    ON return_request_line (tenant_id, return_request_id);
