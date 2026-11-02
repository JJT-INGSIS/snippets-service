CREATE TABLE formatting_configurations (
    id UUID PRIMARY KEY,
    owner_id TEXT NOT NULL CHECK (owner_id ~ '[^[:space:]]'),
    language TEXT NOT NULL CHECK (language ~ '[^[:space:]]'),
    version TEXT NOT NULL CHECK (version ~ '[^[:space:]]'),
    selection JSONB NOT NULL CHECK (jsonb_typeof(selection) = 'object'),
    revision TEXT NOT NULL CHECK (revision ~ '^sha256-v1:[0-9a-f]{64}$'),
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (owner_id, language, version)
);
