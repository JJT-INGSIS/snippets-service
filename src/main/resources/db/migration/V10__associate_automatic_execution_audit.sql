ALTER TABLE snippet_execution_audit ADD COLUMN background_permit_id UUID UNIQUE REFERENCES execution_permits (id);
ALTER TABLE snippet_execution_audit ADD CONSTRAINT automatic_execution_has_background_capacity
    CHECK (background_permit_id IS NULL OR NOT reserved);
CREATE INDEX automatic_execution_results ON snippet_execution_audit (background_permit_id)
    WHERE background_permit_id IS NOT NULL;
