ALTER TABLE period
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE reconciliation
    ADD COLUMN IF NOT EXISTS snapshot_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS nsx_confirmed_by UUID REFERENCES app_users(id),
    ADD COLUMN IF NOT EXISTS npp_confirmed_by UUID REFERENCES app_users(id),
    ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ;

ALTER TABLE reconciliation_exception
    ADD COLUMN IF NOT EXISTS order_si_id UUID REFERENCES order_si(id),
    ADD COLUMN IF NOT EXISTS order_line_id UUID REFERENCES order_line(id),
    ADD COLUMN IF NOT EXISTS expected_qty BIGINT CHECK (expected_qty IS NULL OR expected_qty >= 0),
    ADD COLUMN IF NOT EXISTS actual_qty BIGINT CHECK (actual_qty IS NULL OR actual_qty >= 0),
    ADD COLUMN IF NOT EXISTS resolution TEXT NOT NULL DEFAULT '';

ALTER TABLE period_adjustment
    ADD COLUMN IF NOT EXISTS npp_tenant_id UUID REFERENCES tenant(id),
    ADD COLUMN IF NOT EXISTS metric_key VARCHAR(24) NOT NULL DEFAULT 'RECEIVED';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'reconciliation'::regclass
          AND conname = 'reconciliation_status_check'
    ) THEN
        ALTER TABLE reconciliation
            ADD CONSTRAINT reconciliation_status_check
                CHECK (status IN ('OPEN', 'PARTIALLY_CONFIRMED', 'CONFIRMED'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'period_adjustment'::regclass
          AND conname = 'period_adjustment_metric_check'
    ) THEN
        ALTER TABLE period_adjustment
            ADD CONSTRAINT period_adjustment_metric_check
                CHECK (metric_key IN ('ORDERED', 'SHIPPED', 'RECEIVED'));
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS reconciliation_period_npp_unique_idx
    ON reconciliation (tenant_id, npp_tenant_id, period_id)
    WHERE period_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS reconciliation_exception_line_unique_idx
    ON reconciliation_exception (reconciliation_id, exception_code, order_line_id)
    WHERE order_line_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS reconciliation_tenant_period_status_idx
    ON reconciliation (tenant_id, period_id, status);
CREATE INDEX IF NOT EXISTS reconciliation_exception_tenant_status_idx
    ON reconciliation_exception (tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS period_tenant_status_range_idx
    ON period (tenant_id, status, starts_on, ends_on);
CREATE INDEX IF NOT EXISTS period_adjustment_tenant_period_status_idx
    ON period_adjustment (tenant_id, period_id, status);

CREATE OR REPLACE FUNCTION prevent_closed_period_sellin_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    order_created_at TIMESTAMPTZ;
    order_tenant_id UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        IF TG_TABLE_NAME = 'order_si' THEN
            order_created_at := OLD.created_at;
            order_tenant_id := OLD.tenant_id;
        ELSE
            SELECT os.created_at, os.tenant_id
            INTO order_created_at, order_tenant_id
            FROM order_si os
            WHERE os.id = OLD.order_si_id;
        END IF;

        IF order_created_at IS NOT NULL AND EXISTS (
            SELECT 1 FROM period p
            WHERE p.tenant_id = order_tenant_id
              AND p.status = 'CLOSED'
              AND (order_created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                  BETWEEN p.starts_on AND p.ends_on
        ) THEN
            RAISE EXCEPTION 'Dữ liệu đơn nhập thuộc kỳ đã chốt, không thể chỉnh sửa.'
                USING ERRCODE = '23514', CONSTRAINT = 'period_closed_data_lock';
        END IF;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        IF TG_TABLE_NAME = 'order_si' THEN
            order_created_at := NEW.created_at;
            order_tenant_id := NEW.tenant_id;
        ELSIF NEW.order_si_id IS NOT NULL THEN
            SELECT os.created_at, os.tenant_id
            INTO order_created_at, order_tenant_id
            FROM order_si os
            WHERE os.id = NEW.order_si_id;
        ELSE
            order_created_at := NULL;
            order_tenant_id := NULL;
        END IF;

        IF order_created_at IS NOT NULL AND EXISTS (
            SELECT 1 FROM period p
            WHERE p.tenant_id = order_tenant_id
              AND p.status = 'CLOSED'
              AND (order_created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                  BETWEEN p.starts_on AND p.ends_on
        ) THEN
            RAISE EXCEPTION 'Dữ liệu đơn nhập thuộc kỳ đã chốt, không thể chỉnh sửa.'
                USING ERRCODE = '23514', CONSTRAINT = 'period_closed_data_lock';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'order_si_closed_period_lock'
          AND NOT tgisinternal
    ) THEN
        CREATE TRIGGER order_si_closed_period_lock
            BEFORE INSERT OR UPDATE OR DELETE ON order_si
            FOR EACH ROW EXECUTE FUNCTION prevent_closed_period_sellin_mutation();
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'order_line_closed_period_lock'
          AND NOT tgisinternal
    ) THEN
        CREATE TRIGGER order_line_closed_period_lock
            BEFORE INSERT OR UPDATE OR DELETE ON order_line
            FOR EACH ROW
            EXECUTE FUNCTION prevent_closed_period_sellin_mutation();
    END IF;
END
$$;
