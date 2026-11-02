CREATE TABLE execution_capacity (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    active_limit INTEGER NOT NULL CHECK (active_limit > 0),
    manual_reserve INTEGER NOT NULL CHECK (manual_reserve >= 1 AND manual_reserve <= active_limit)
);
INSERT INTO execution_capacity (id, active_limit, manual_reserve) VALUES (1, 4, 1);

CREATE TABLE execution_permits (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('MANUAL', 'BACKGROUND')),
    job_id UUID REFERENCES background_jobs (id),
    item_id UUID REFERENCES background_job_items (id),
    manual_run_id UUID UNIQUE REFERENCES snippet_execution_audit (id),
    state TEXT NOT NULL CHECK (state IN ('RESERVED', 'UNKNOWN', 'RELEASED')),
    remote JSONB CHECK (remote IS NULL OR jsonb_typeof(remote) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolution_evidence TEXT,
    CHECK ((kind = 'MANUAL' AND manual_run_id IS NOT NULL AND job_id IS NULL AND item_id IS NULL)
        OR (kind = 'BACKGROUND' AND manual_run_id IS NULL AND job_id IS NOT NULL AND item_id IS NOT NULL))
);
-- A permit has no TTL: a worker lease expiring does not stop the remote execution.
CREATE UNIQUE INDEX one_background_permit ON execution_permits (kind)
    WHERE kind = 'BACKGROUND' AND state <> 'RELEASED';
CREATE INDEX held_execution_permits ON execution_permits (job_id, created_at) WHERE state <> 'RELEASED';
INSERT INTO execution_permits (id, kind, manual_run_id, state, created_at)
    SELECT id, 'MANUAL', id, 'UNKNOWN', started_at FROM snippet_execution_audit WHERE reserved;

CREATE TABLE rejected_stream_deliveries (
    stream TEXT NOT NULL,
    record_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    payload_bytes INTEGER NOT NULL,
    rejected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stream, record_id)
);
