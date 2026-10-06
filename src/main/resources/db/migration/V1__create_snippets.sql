CREATE TABLE snippets (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (name ~ '[^[:space:]]'),
    description TEXT,
    language TEXT NOT NULL CHECK (language ~ '[^[:space:]]'),
    version TEXT NOT NULL CHECK (version ~ '[^[:space:]]'),
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'CONFIRMED')),
    creation_key UUID NOT NULL UNIQUE,
    creation_actor_id TEXT NOT NULL CHECK (creation_actor_id ~ '[^[:space:]]'),
    creation_fingerprint TEXT NOT NULL CHECK (creation_fingerprint ~ '[^[:space:]]')
);
