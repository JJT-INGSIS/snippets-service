-- A reference activation does not deploy or switch the running processor.
CREATE TABLE parser_activation_scopes (
    language TEXT NOT NULL,
    version TEXT NOT NULL,
    active_activation UUID,
    PRIMARY KEY (language, version)
);

CREATE TABLE parser_activations (
    id UUID PRIMARY KEY,
    language TEXT NOT NULL,
    version TEXT NOT NULL,
    processor_revision TEXT NOT NULL,
    operator_identity TEXT NOT NULL,
    reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 250),
    job_id UUID NOT NULL UNIQUE REFERENCES background_jobs(id),
    activated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (language, version) REFERENCES parser_activation_scopes(language, version)
);
ALTER TABLE parser_activation_scopes ADD FOREIGN KEY (active_activation) REFERENCES parser_activations(id);

CREATE TABLE parser_validation_snapshots (
    job_id UUID NOT NULL REFERENCES background_jobs(id),
    snippet_id UUID NOT NULL REFERENCES snippets(id),
    source_reference TEXT NOT NULL,
    language TEXT NOT NULL,
    version TEXT NOT NULL,
    PRIMARY KEY (job_id, snippet_id)
);
CREATE INDEX parser_validation_snapshot_retention ON parser_validation_snapshots (source_reference, job_id);

CREATE TABLE parser_validation_results (
    snippet_id UUID PRIMARY KEY REFERENCES snippets(id),
    result_id UUID NOT NULL UNIQUE,
    activation_id UUID NOT NULL REFERENCES parser_activations(id),
    source_reference TEXT NOT NULL,
    processor_revision TEXT NOT NULL,
    valid BOOLEAN NOT NULL,
    diagnostics JSONB NOT NULL CHECK (jsonb_typeof(diagnostics) = 'array'),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- A bounded analysis admission is NOT a remote session/termination reservation.
-- Only this lane expires; execution_permits retains its original no-TTL semantics.
CREATE TABLE parser_analysis_admission (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    token UUID NOT NULL,
    job_id UUID NOT NULL REFERENCES background_jobs(id),
    item_id UUID NOT NULL REFERENCES background_job_items(id),
    expires_at TIMESTAMPTZ NOT NULL
);
