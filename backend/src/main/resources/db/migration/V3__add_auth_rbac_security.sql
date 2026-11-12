ALTER TABLE role
    ADD COLUMN assignment_level INTEGER NOT NULL DEFAULT 0
        CHECK (assignment_level BETWEEN 0 AND 100);

ALTER TABLE refresh_token
    ADD COLUMN family_id UUID,
    ADD COLUMN device_id VARCHAR(128),
    ADD COLUMN rotated_from UUID REFERENCES refresh_token(id),
    ADD COLUMN replaced_by UUID REFERENCES refresh_token(id);

UPDATE refresh_token
SET family_id = id,
    device_id = 'legacy'
WHERE family_id IS NULL OR device_id IS NULL;

ALTER TABLE refresh_token
    ALTER COLUMN family_id SET NOT NULL,
    ALTER COLUMN device_id SET NOT NULL;

CREATE INDEX refresh_token_tenant_family_idx ON refresh_token (tenant_id, user_id, family_id);

ALTER TABLE audit_log
    ADD COLUMN device_id VARCHAR(128);

CREATE TABLE auth_login_attempt (
    email VARCHAR(254) PRIMARY KEY,
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
