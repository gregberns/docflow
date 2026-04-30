-- C3 processing pipeline tables. Stitched into V1__init.sql by C7.4.
-- Source-of-truth: c3-pipeline-spec.md §3.7, §4.
-- Cross-fragment FK targets: stored_documents (C2), documents (C4). C7.4 orders
-- those fragments earlier in V1 so these FKs resolve at migrate time.

CREATE TABLE processing_documents (
    id                 UUID         PRIMARY KEY,
    stored_document_id UUID         NOT NULL REFERENCES stored_documents (id),
    organization_id    VARCHAR(255) NOT NULL,
    current_step       VARCHAR(32)  NOT NULL,
    raw_text           TEXT,
    last_error         TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_processing_documents_current_step
        CHECK (current_step IN ('TEXT_EXTRACTING', 'CLASSIFYING', 'EXTRACTING', 'FAILED'))
);

CREATE INDEX idx_processing_documents_org_created
    ON processing_documents (organization_id, created_at DESC);

CREATE INDEX idx_processing_documents_stored
    ON processing_documents (stored_document_id);

CREATE INDEX idx_processing_documents_step_updated
    ON processing_documents (current_step, updated_at);

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
