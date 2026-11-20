CREATE TABLE linting_configurations (
    id UUID PRIMARY KEY,
    owner_id TEXT NOT NULL,
    language TEXT NOT NULL,
    version TEXT NOT NULL,
    selection JSONB NOT NULL,
    revision TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, language, version)
);

CREATE TABLE snippet_lint_assessments (
    id UUID PRIMARY KEY,
    snippet_id UUID NOT NULL REFERENCES snippets(id),
    owner_id TEXT NOT NULL,
    language TEXT NOT NULL,
    version TEXT NOT NULL,
    content_reference TEXT NOT NULL,
    code_hash TEXT NOT NULL,
    rules_revision TEXT,
    processor_revision TEXT,
    state TEXT NOT NULL CHECK (state IN ('COMPLIANT', 'NON_COMPLIANT', 'NOT_EVALUATED_ERROR')),
    diagnostics JSONB NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX snippet_lint_assessments_by_snippet ON snippet_lint_assessments(snippet_id, created_at DESC);
ALTER TABLE snippets ADD COLUMN current_lint_assessment UUID REFERENCES snippet_lint_assessments(id);
