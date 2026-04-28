-- C2 ingestion table. Stitched into V1__init.sql by C7.4 after c1-org-config.sql.
-- Source-of-truth: c2-ingestion-spec.md §4.2.
-- organization_id is VARCHAR(255) to FK into organizations(id), which C1 owns
-- as a slug-typed primary key (c1-org-config.sql). The spec narrative names
-- the column UUID; the surrounding repo (StoredDocument record, C3 events)
-- carries that mismatch — flagged for cross-task resolution. The DDL has to
-- match its referent for the FK to resolve.

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
