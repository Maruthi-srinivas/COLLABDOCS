ALTER TABLE documents
    ADD COLUMN header_text text NOT NULL DEFAULT '',
    ADD COLUMN footer_text text NOT NULL DEFAULT '',
    ADD COLUMN page_numbers boolean NOT NULL DEFAULT false;

INSERT INTO templates (id, name, description, snapshot_object_key, category) VALUES
    ('00000000-0000-7000-8000-000000000001', 'Blank', 'An empty document', 'templates/blank.bin', 'system'),
    ('00000000-0000-7000-8000-000000000002', 'Letter', 'A short letter', 'templates/letter.bin', 'system'),
    ('00000000-0000-7000-8000-000000000003', 'Meeting notes', 'Notes for a meeting', 'templates/meeting-notes.bin', 'system'),
    ('00000000-0000-7000-8000-000000000004', 'Project brief', 'A project brief', 'templates/project-brief.bin', 'system');
