CREATE TABLE background_jobs (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    reaction TEXT NOT NULL CHECK (length(reaction) > 0),
    initiated_by TEXT CHECK (initiated_by IS NULL OR length(initiated_by) > 0),
    scope JSONB NOT NULL CHECK (jsonb_typeof(scope) = 'object'),
    revisions JSONB NOT NULL CHECK (jsonb_typeof(revisions) = 'object'),
    state TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'RUNNING', 'RETRY', 'UNKNOWN', 'COMPLETED', 'PARTIAL', 'FAILED', 'SUPERSEDED')),
    selection_cursor TEXT,
    selection_complete BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    last_error TEXT CHECK (length(last_error) <= 500),
    UNIQUE (event_id, reaction),
    UNIQUE (id, event_id),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((state IN ('COMPLETED', 'PARTIAL', 'FAILED', 'SUPERSEDED')) = (finished_at IS NOT NULL))
);

CREATE TABLE background_job_items (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES background_jobs (id),
    item_key TEXT NOT NULL CHECK (length(item_key) > 0),
    revisions JSONB NOT NULL CHECK (jsonb_typeof(revisions) = 'object'),
    snapshot_reference TEXT,
    state TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'RUNNING', 'RETRY', 'UNKNOWN', 'COMPLETED', 'FAILED', 'SUPERSEDED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    result_reference TEXT,
    last_error TEXT CHECK (length(last_error) <= 500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (job_id, item_key)
);

CREATE TABLE event_outbox (
    event_id UUID PRIMARY KEY,
    job_id UUID NOT NULL UNIQUE,
    envelope JSONB NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    redis_record_id TEXT,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error TEXT CHECK (length(last_error) <= 500),
    FOREIGN KEY (job_id, event_id) REFERENCES background_jobs (id, event_id),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((published_at IS NULL) = (redis_record_id IS NULL))
);

CREATE INDEX event_outbox_pending ON event_outbox (next_attempt_at, created_at, event_id)
    WHERE published_at IS NULL;
CREATE INDEX background_jobs_pending ON background_jobs (next_attempt_at, created_at, id)
    WHERE finished_at IS NULL;
CREATE INDEX background_job_items_progress ON background_job_items (job_id, state, next_attempt_at);
