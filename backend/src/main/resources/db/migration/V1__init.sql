-- Flyway baseline assembled by C7.4 from the four V1 fragments under
-- db/migration/fragments/. Canonical order matters: cross-fragment FKs
-- (llm_call_audit -> documents, documents -> stored_documents, etc.)
-- only resolve when each referenced table already exists. The fragments
-- themselves are still consumed by per-component fragment-level ITs and
-- remain the source-of-truth narrative for each table's contract.

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

CREATE TABLE stored_documents (
    id              UUID         PRIMARY KEY,
    organization_id VARCHAR(255) NOT NULL,
    uploaded_at     TIMESTAMPTZ  NOT NULL,
    source_filename TEXT         NOT NULL,
    mime_type       TEXT         NOT NULL,
    storage_path    TEXT         NOT NULL,
    CONSTRAINT fk_stored_documents_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id)
);

CREATE INDEX idx_stored_documents_org_uploaded
    ON stored_documents (organization_id, uploaded_at DESC);

CREATE TABLE processing_documents (
    id                 UUID         PRIMARY KEY,
    stored_document_id UUID         NOT NULL REFERENCES stored_documents (id),
    organization_id    VARCHAR(255) NOT NULL,
    current_step       VARCHAR(32)  NOT NULL,
    raw_text           TEXT,
    last_error         TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_processing_documents_current_step
        CHECK (current_step IN ('TEXT_EXTRACTING', 'CLASSIFYING', 'EXTRACTING', 'FAILED'))
);

CREATE INDEX idx_processing_documents_org_created
    ON processing_documents (organization_id, created_at DESC);

CREATE INDEX idx_processing_documents_stored
    ON processing_documents (stored_document_id);

CREATE TABLE documents (
    id                     UUID         PRIMARY KEY,
    stored_document_id     UUID         NOT NULL REFERENCES stored_documents (id),
    organization_id        VARCHAR(255) NOT NULL,
    detected_document_type VARCHAR(255) NOT NULL,
    extracted_fields       JSONB        NOT NULL,
    raw_text               TEXT,
    processed_at           TIMESTAMPTZ  NOT NULL,
    reextraction_status    VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    CONSTRAINT fk_documents_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_documents_document_type
        FOREIGN KEY (organization_id, detected_document_type)
        REFERENCES document_types (organization_id, id),
    CONSTRAINT ck_documents_reextraction_status
        CHECK (reextraction_status IN ('NONE', 'IN_PROGRESS', 'FAILED'))
);

CREATE UNIQUE INDEX idx_documents_stored_document_id
    ON documents (stored_document_id);

CREATE INDEX idx_documents_org_processed_at
    ON documents (organization_id, processed_at DESC);

CREATE TABLE workflow_instances (
    id                    UUID         PRIMARY KEY,
    document_id           UUID         NOT NULL REFERENCES documents (id),
    organization_id       VARCHAR(255) NOT NULL,
    document_type_id      VARCHAR(255) NOT NULL,
    current_stage_id      VARCHAR(255) NOT NULL,
    current_status        VARCHAR(32)  NOT NULL,
    workflow_origin_stage VARCHAR(255),
    flag_comment          TEXT,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_workflow_instances_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_workflow_instances_current_stage
        FOREIGN KEY (organization_id, document_type_id, current_stage_id)
        REFERENCES stages (organization_id, document_type_id, id),
    CONSTRAINT ck_workflow_instances_current_status
        CHECK (current_status IN (
            'AWAITING_REVIEW', 'FLAGGED', 'AWAITING_APPROVAL', 'FILED', 'REJECTED'))
);

CREATE UNIQUE INDEX idx_workflow_instances_document_id
    ON workflow_instances (document_id);

CREATE INDEX idx_workflow_instances_org_status_updated
    ON workflow_instances (organization_id, current_status, updated_at DESC);

CREATE TABLE llm_call_audit (
    id                     UUID         PRIMARY KEY,
    stored_document_id     UUID         NOT NULL REFERENCES stored_documents (id),
    processing_document_id UUID         REFERENCES processing_documents (id) ON DELETE SET NULL,
    document_id            UUID         REFERENCES documents (id) ON DELETE SET NULL,
    organization_id        VARCHAR(255) NOT NULL,
    call_type              VARCHAR(16)  NOT NULL,
    model_id               VARCHAR(64)  NOT NULL,
    error                  TEXT,
    at                     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_llm_call_audit_call_type
        CHECK (call_type IN ('classify', 'extract')),
    CONSTRAINT ck_llm_call_audit_subject_exclusive
        CHECK (
            (processing_document_id IS NOT NULL AND document_id IS NULL)
            OR (processing_document_id IS NULL AND document_id IS NOT NULL)
        )
);

CREATE INDEX idx_llm_call_audit_stored_at
    ON llm_call_audit (stored_document_id, at DESC);

CREATE INDEX idx_llm_call_audit_proc
    ON llm_call_audit (processing_document_id);

CREATE INDEX idx_llm_call_audit_doc
    ON llm_call_audit (document_id);
