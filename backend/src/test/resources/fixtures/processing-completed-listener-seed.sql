INSERT INTO organizations (id, display_name, icon_id, ordinal)
VALUES ('test-org', 'Test Org', 'icon-test', 0);

INSERT INTO document_types (organization_id, id, display_name, input_modality, field_schema)
VALUES ('test-org', 'test-doc-type', 'Test Doc Type', 'PDF', '{"fields": []}');

INSERT INTO organization_doc_types (organization_id, document_type_id, ordinal)
VALUES ('test-org', 'test-doc-type', 0);

INSERT INTO workflows (organization_id, document_type_id)
VALUES ('test-org', 'test-doc-type');

INSERT INTO stages
    (organization_id, document_type_id, id, display_name, kind, canonical_status, role, ordinal)
VALUES
    ('test-org', 'test-doc-type', 'stage-rv',  'rv-display', 'REVIEW',   'AWAITING_REVIEW',   NULL, 0),
    ('test-org', 'test-doc-type', 'stage-fl',  'fl-display', 'TERMINAL', 'FILED',             NULL, 1),
    ('test-org', 'test-doc-type', 'stage-rj',  'rj-display', 'TERMINAL', 'REJECTED',          NULL, 2);
