CREATE TABLE snippet_execution_audit (
    id UUID PRIMARY KEY,
    actor_id TEXT NOT NULL CHECK (length(actor_id) > 0),
    snippet_id UUID NOT NULL REFERENCES snippets (id),
    test_id UUID NOT NULL REFERENCES snippet_test_cases (id),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    started_at TIMESTAMPTZ NOT NULL,
    remote JSONB CHECK (remote IS NULL OR jsonb_typeof(remote) = 'object'),
    result JSONB CHECK (result IS NULL OR jsonb_typeof(result) = 'object'),
    finished_at TIMESTAMPTZ,
    failure TEXT,
    reserved BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (result IS NULL OR (finished_at IS NOT NULL AND NOT reserved))
);

-- Both modes must use this reservation when interactive coordination is added.
-- Unknown starts remain reserved: a missing acknowledgment does not prove absence.
CREATE UNIQUE INDEX execution_one_reserved_per_actor ON snippet_execution_audit (actor_id) WHERE reserved;
CREATE INDEX execution_audit_by_snippet ON snippet_execution_audit (snippet_id, started_at, id);
