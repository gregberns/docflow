-- C1 client-data tables. Stitched into V1__init.sql by C7.4.
-- Source-of-truth: c1-config-spec.md §4.2, §4.3.

CREATE TABLE organizations (
    id           VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    icon_id      VARCHAR(255) NOT NULL,
    ordinal      INT          NOT NULL
);

CREATE TABLE document_types (
    organization_id VARCHAR(255) NOT NULL,
    id              VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255) NOT NULL,
    field_schema    JSONB        NOT NULL,
    PRIMARY KEY (organization_id, id),
    CONSTRAINT fk_document_types_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id)
);

CREATE INDEX idx_document_types_organization
    ON document_types (organization_id);

CREATE TABLE organization_doc_types (
    organization_id  VARCHAR(255) NOT NULL,
    document_type_id VARCHAR(255) NOT NULL,
    ordinal          INT          NOT NULL,
    PRIMARY KEY (organization_id, document_type_id),
    CONSTRAINT fk_org_doc_types_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_org_doc_types_document_type
        FOREIGN KEY (organization_id, document_type_id)
        REFERENCES document_types (organization_id, id)
);

CREATE INDEX idx_organization_doc_types_organization
    ON organization_doc_types (organization_id);

CREATE INDEX idx_organization_doc_types_document_type
    ON organization_doc_types (organization_id, document_type_id);

CREATE TABLE workflows (
    organization_id  VARCHAR(255) NOT NULL,
    document_type_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (organization_id, document_type_id),
    CONSTRAINT fk_workflows_document_type
        FOREIGN KEY (organization_id, document_type_id)
        REFERENCES document_types (organization_id, id)
);

CREATE INDEX idx_workflows_document_type
    ON workflows (organization_id, document_type_id);

CREATE TABLE stages (
    organization_id  VARCHAR(255) NOT NULL,
    document_type_id VARCHAR(255) NOT NULL,
    id               VARCHAR(255) NOT NULL,
    display_name     VARCHAR(255),
    kind             VARCHAR(16)  NOT NULL,
    canonical_status VARCHAR(32)  NOT NULL,
    role             VARCHAR(64),
    ordinal          INT          NOT NULL,
    PRIMARY KEY (organization_id, document_type_id, id),
    CONSTRAINT fk_stages_workflow
        FOREIGN KEY (organization_id, document_type_id)
        REFERENCES workflows (organization_id, document_type_id),
    CONSTRAINT ck_stages_kind
        CHECK (kind IN ('REVIEW', 'APPROVAL', 'TERMINAL')),
    CONSTRAINT ck_stages_canonical_status
        CHECK (canonical_status IN (
            'AWAITING_REVIEW', 'AWAITING_APPROVAL', 'FILED', 'REJECTED'))
);

CREATE INDEX idx_stages_workflow
    ON stages (organization_id, document_type_id, ordinal);

CREATE TABLE transitions (
    id               UUID         PRIMARY KEY,
    organization_id  VARCHAR(255) NOT NULL,
    document_type_id VARCHAR(255) NOT NULL,
    from_stage       VARCHAR(255) NOT NULL,
    to_stage         VARCHAR(255) NOT NULL,
    action           VARCHAR(16)  NOT NULL,
    guard_field      VARCHAR(128),
    guard_op         VARCHAR(8),
    guard_value      VARCHAR(255),
    ordinal          INT          NOT NULL,
    CONSTRAINT fk_transitions_workflow
        FOREIGN KEY (organization_id, document_type_id)
        REFERENCES workflows (organization_id, document_type_id),
    CONSTRAINT fk_transitions_from_stage
        FOREIGN KEY (organization_id, document_type_id, from_stage)
        REFERENCES stages (organization_id, document_type_id, id),
    CONSTRAINT fk_transitions_to_stage
        FOREIGN KEY (organization_id, document_type_id, to_stage)
        REFERENCES stages (organization_id, document_type_id, id),
    CONSTRAINT ck_transitions_action
        CHECK (action IN ('AUTO_ADVANCE', 'APPROVE', 'REJECT', 'FLAG', 'RESOLVE')),
    CONSTRAINT ck_transitions_guard_op
        CHECK (guard_op IS NULL OR guard_op IN ('EQ', 'NEQ'))
);

CREATE INDEX idx_transitions_workflow
    ON transitions (organization_id, document_type_id);

CREATE INDEX idx_transitions_from_stage
    ON transitions (organization_id, document_type_id, from_stage);

CREATE INDEX idx_transitions_to_stage
    ON transitions (organization_id, document_type_id, to_stage);
