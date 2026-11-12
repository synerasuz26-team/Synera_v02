ALTER TABLE route_outlet
    ADD COLUMN IF NOT EXISTS latitude NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS longitude NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS gps_radius_m INTEGER NOT NULL DEFAULT 150;

ALTER TABLE visit
    ADD COLUMN IF NOT EXISTS checkout_latitude NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS checkout_longitude NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS checkout_distance_m INTEGER,
    ADD COLUMN IF NOT EXISTS checkin_gps_exception_id UUID,
    ADD COLUMN IF NOT EXISTS checkout_gps_exception_id UUID;

ALTER TABLE mcp_schedule
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'route_outlet'::regclass AND conname = 'route_outlet_gps_coordinates_check'
    ) THEN
        ALTER TABLE route_outlet
            ADD CONSTRAINT route_outlet_gps_coordinates_check
                CHECK ((latitude IS NULL AND longitude IS NULL)
                    OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'route_outlet'::regclass AND conname = 'route_outlet_gps_radius_check'
    ) THEN
        ALTER TABLE route_outlet
            ADD CONSTRAINT route_outlet_gps_radius_check CHECK (gps_radius_m BETWEEN 1 AND 10000);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'visit'::regclass AND conname = 'visit_checkout_distance_nonnegative'
    ) THEN
        ALTER TABLE visit
            ADD CONSTRAINT visit_checkout_distance_nonnegative
                CHECK (checkout_distance_m IS NULL OR checkout_distance_m >= 0);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'visit'::regclass AND conname = 'visit_checkout_gps_coordinates_check'
    ) THEN
        ALTER TABLE visit
            ADD CONSTRAINT visit_checkout_gps_coordinates_check
                CHECK ((checkout_latitude IS NULL AND checkout_longitude IS NULL)
                    OR (checkout_latitude BETWEEN -90 AND 90 AND checkout_longitude BETWEEN -180 AND 180));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS visit_gps_exception (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    visit_id UUID NOT NULL REFERENCES visit(id),
    action VARCHAR(12) NOT NULL CHECK (action IN ('CHECK_IN', 'CHECK_OUT')),
    latitude NUMERIC(10, 7) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude NUMERIC(10, 7) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    distance_m INTEGER CHECK (distance_m IS NULL OR distance_m >= 0),
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by UUID NOT NULL REFERENCES app_users(id),
    reviewed_by UUID REFERENCES app_users(id),
    reviewed_at TIMESTAMPTZ,
    review_reason VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((status = 'PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR (status <> 'PENDING' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS visit_reassignment_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    visit_id UUID NOT NULL REFERENCES visit(id),
    previous_sales_user_id UUID NOT NULL REFERENCES app_users(id),
    next_sales_user_id UUID NOT NULL REFERENCES app_users(id),
    reassigned_by UUID NOT NULL REFERENCES app_users(id),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (previous_sales_user_id <> next_sales_user_id)
);

CREATE TABLE IF NOT EXISTS checklist_template (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    definition JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, code)
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'visit'::regclass AND conname = 'visit_checkin_gps_exception_fk'
    ) THEN
        ALTER TABLE visit
            ADD CONSTRAINT visit_checkin_gps_exception_fk
                FOREIGN KEY (checkin_gps_exception_id) REFERENCES visit_gps_exception(id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'visit'::regclass AND conname = 'visit_checkout_gps_exception_fk'
    ) THEN
        ALTER TABLE visit
            ADD CONSTRAINT visit_checkout_gps_exception_fk
                FOREIGN KEY (checkout_gps_exception_id) REFERENCES visit_gps_exception(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS route_outlet_gps_lookup_idx
    ON route_outlet (tenant_id, route_id, outlet_id, effective_from DESC);
CREATE INDEX IF NOT EXISTS visit_gps_exception_pending_idx
    ON visit_gps_exception (tenant_id, status, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS visit_gps_exception_one_pending_action_idx
    ON visit_gps_exception (tenant_id, visit_id, action)
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS visit_reassignment_timeline_idx
    ON visit_reassignment_history (tenant_id, visit_id, created_at DESC);
CREATE INDEX IF NOT EXISTS checklist_template_tenant_status_idx
    ON checklist_template (tenant_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS attendance_tenant_date_idx
    ON attendance (tenant_id, attendance_date DESC);
