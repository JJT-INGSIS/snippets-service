ALTER TABLE snippets
    ADD COLUMN content_reference TEXT CHECK (content_reference ~ '[^[:space:]]');