CREATE INDEX IF NOT EXISTS idx_documents_org_doctype
  ON documents (organization_id, detected_document_type);
