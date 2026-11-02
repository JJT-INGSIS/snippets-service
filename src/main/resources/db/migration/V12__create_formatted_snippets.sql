CREATE TABLE formatted_snippets (
    snippet_id UUID PRIMARY KEY REFERENCES snippets (id),
    source_reference TEXT NOT NULL CHECK (source_reference ~ '[^[:space:]]'),
    language TEXT NOT NULL CHECK (language ~ '[^[:space:]]'),
    version TEXT NOT NULL CHECK (version ~ '[^[:space:]]'),
    rules_revision TEXT NOT NULL CHECK (rules_revision ~ '^sha256-v1:[0-9a-f]{64}$'),
    processor_revision TEXT NOT NULL CHECK (processor_revision ~ '^sha256-v1:[0-9a-f]{64}$'),
    derived_reference TEXT NOT NULL UNIQUE CHECK (derived_reference ~ '[^[:space:]]'),
    job_id UUID NOT NULL REFERENCES background_jobs (id),
    published_at TIMESTAMPTZ NOT NULL
);
