CREATE TABLE IF NOT EXISTS background_job_state (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    job_name VARCHAR(60) NOT NULL,
    last_run_key VARCHAR(100) NOT NULL,
    last_run_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, job_name)
);

-- Older NIGHTLY run keys represented the execution day; the job processed the previous business day.
INSERT INTO background_job_state (tenant_id, job_name, last_run_key, last_run_at)
SELECT DISTINCT ON (tenant_id)
    tenant_id, 'NIGHTLY',
    (run_key::date - 1)::text,
    finished_at
FROM background_job_run
WHERE job_name = 'NIGHTLY'
  AND status = 'SUCCEEDED'
  AND run_key ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
ORDER BY tenant_id, run_key::date DESC, finished_at DESC
ON CONFLICT (tenant_id, job_name) DO NOTHING;

CREATE INDEX IF NOT EXISTS background_job_state_updated_idx
    ON background_job_state (updated_at DESC);
