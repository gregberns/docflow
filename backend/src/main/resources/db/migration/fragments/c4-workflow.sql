-- C4 workflow tables. Stitched into V1__init.sql by C7.4.
-- Source-of-truth: c4-workflow-spec.md §4.2 (C4-R11, C4-R13).
-- Cross-fragment FK targets: stored_documents (C2), document_types + stages (C1).
-- C7.4 orders those fragments earlier in V1 so these FKs resolve at migrate time.

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
