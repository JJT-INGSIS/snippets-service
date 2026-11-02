CREATE TABLE retained_content (
    reference TEXT PRIMARY KEY,
    state TEXT NOT NULL DEFAULT 'AVAILABLE' CHECK (state IN ('AVAILABLE', 'DELETING', 'DELETED')),
    cleanup_token UUID,
    cleanup_until TIMESTAMPTZ,
    last_error TEXT,
    CHECK ((cleanup_token IS NULL) = (cleanup_until IS NULL))
);

CREATE TABLE snippet_update_snapshots (
    job_id UUID PRIMARY KEY REFERENCES background_jobs (id) DEFERRABLE INITIALLY DEFERRED,
    snippet_id UUID NOT NULL REFERENCES snippets (id),
    actor_id TEXT NOT NULL CHECK (length(actor_id) > 0),
    language TEXT NOT NULL,
    version TEXT NOT NULL,
    content_reference TEXT NOT NULL REFERENCES retained_content (reference),
    code_hash TEXT NOT NULL,
    tests JSONB NOT NULL CHECK (jsonb_typeof(tests) = 'array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMPTZ
);
CREATE INDEX retained_update_content ON snippet_update_snapshots (content_reference)
    WHERE released_at IS NULL;
