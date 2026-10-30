ALTER TABLE snippet_execution_audit ALTER COLUMN test_id DROP NOT NULL;

ALTER TABLE snippet_execution_audit ADD COLUMN mode TEXT NOT NULL DEFAULT 'TEST';
ALTER TABLE snippet_execution_audit ALTER COLUMN mode DROP DEFAULT;

ALTER TABLE snippet_execution_audit ADD CONSTRAINT execution_audit_mode_matches_test CHECK (
    (mode = 'TEST' AND test_id IS NOT NULL) OR (mode = 'INTERACTIVE' AND test_id IS NULL)
);

UPDATE snippet_execution_audit
SET snapshot = (snapshot - 'testId' - 'definition' - 'testHash') || jsonb_build_object(
    'test', jsonb_build_object('id', snapshot -> 'testId', 'definition', snapshot -> 'definition', 'hash', snapshot -> 'testHash')
);
