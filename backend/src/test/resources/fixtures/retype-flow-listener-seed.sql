INSERT INTO organizations (id, display_name, icon_id, ordinal)
VALUES ('test-org', 'Test Org', 'icon-test', 0);

INSERT INTO document_types (organization_id, id, display_name, input_modality, field_schema)
VALUES
    ('test-org', 'invoice', 'Invoice', 'PDF', '{"fields": []}'),
    ('test-org', 'receipt', 'Receipt', 'PDF', '{"fields": []}');

INSERT INTO organization_doc_types (organization_id, document_type_id, ordinal)
VALUES
    ('test-org', 'invoice', 0),
    ('test-org', 'receipt', 1);

INSERT INTO workflows (organization_id, document_type_id)
VALUES
    ('test-org', 'invoice'),
    ('test-org', 'receipt');

INSERT INTO stages
    (organization_id, document_type_id, id, display_name, kind, canonical_status, role, ordinal)
VALUES
    ('test-org', 'invoice', 'stage-rv-inv', 'Review',   'REVIEW',   'AWAITING_REVIEW',   NULL,      0),
    ('test-org', 'invoice', 'stage-mg-inv', 'Manager',  'APPROVAL', 'AWAITING_APPROVAL', 'manager', 1),
    ('test-org', 'invoice', 'stage-fl-inv', 'Filed',    'TERMINAL', 'FILED',             NULL,      2),
    ('test-org', 'invoice', 'stage-rj-inv', 'Rejected', 'TERMINAL', 'REJECTED',          NULL,      3),
    ('test-org', 'receipt', 'stage-rv-rec', 'Review',   'REVIEW',   'AWAITING_REVIEW',   NULL,      0),
    ('test-org', 'receipt', 'stage-fl-rec', 'Filed',    'TERMINAL', 'FILED',             NULL,      1),
    ('test-org', 'receipt', 'stage-rj-rec', 'Rejected', 'TERMINAL', 'REJECTED',          NULL,      2);

INSERT INTO transitions
    (id, organization_id, document_type_id, from_stage, to_stage, action, guard_field, guard_op, guard_value, ordinal)
VALUES
    (gen_random_uuid(), 'test-org', 'invoice', 'stage-rv-inv', 'stage-mg-inv', 'APPROVE', NULL, NULL, NULL, 0),
    (gen_random_uuid(), 'test-org', 'invoice', 'stage-rv-inv', 'stage-rj-inv', 'REJECT',  NULL, NULL, NULL, 1),
    (gen_random_uuid(), 'test-org', 'invoice', 'stage-mg-inv', 'stage-fl-inv', 'APPROVE', NULL, NULL, NULL, 2),
    (gen_random_uuid(), 'test-org', 'invoice', 'stage-mg-inv', 'stage-rv-inv', 'FLAG',    NULL, NULL, NULL, 3),
    (gen_random_uuid(), 'test-org', 'receipt', 'stage-rv-rec', 'stage-fl-rec', 'APPROVE', NULL, NULL, NULL, 0),
    (gen_random_uuid(), 'test-org', 'receipt', 'stage-rv-rec', 'stage-rj-rec', 'REJECT',  NULL, NULL, NULL, 1);
