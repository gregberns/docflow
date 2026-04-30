package com.docflow.ingestion.internal;

import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentWriter;
import org.springframework.stereotype.Component;

@Component
class StoredDocumentJpaWriter implements StoredDocumentWriter {

  private final StoredDocumentEntityRepository repository;

  StoredDocumentJpaWriter(StoredDocumentEntityRepository repository) {
    this.repository = repository;
  }

  @Override
  public void insert(StoredDocument document) {
    // saveAndFlush so a same-transaction JDBC read (e.g. the JDBC processing-document writer's
    // FK target) sees the row. Without flushing, the persistence context defers the INSERT
    // to commit time and the FK lookup fails.
    repository.saveAndFlush(
        new StoredDocumentEntity(
            document.id().value(),
            document.organizationId(),
            document.uploadedAt(),
            document.sourceFilename(),
            document.mimeType(),
            document.storagePath()));
  }
}
