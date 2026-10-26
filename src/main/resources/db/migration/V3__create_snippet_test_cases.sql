CREATE TABLE snippet_test_cases (
    id UUID PRIMARY KEY,
    snippet_id UUID NOT NULL REFERENCES snippets (id),
    creation_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    inputs JSONB NOT NULL CHECK (
        jsonb_typeof(inputs) = 'array' AND NOT jsonb_path_exists(inputs, '$[*] ? (@.type() != "string")')
    ),
    expected_outputs JSONB NOT NULL CHECK (
        jsonb_typeof(expected_outputs) = 'array'
        AND NOT jsonb_path_exists(expected_outputs, '$[*] ? (@.type() != "string")')
    )
);

CREATE INDEX snippet_test_cases_by_snippet ON snippet_test_cases (snippet_id, creation_order);