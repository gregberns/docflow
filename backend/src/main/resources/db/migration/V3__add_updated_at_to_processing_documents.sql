ALTER TABLE processing_documents
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_processing_documents_step_updated
  ON processing_documents (current_step, updated_at);
