ALTER TABLE suggestions
    ADD COLUMN anchor_state text NOT NULL DEFAULT 'bound'
    CHECK (anchor_state IN ('bound', 'detached'));
