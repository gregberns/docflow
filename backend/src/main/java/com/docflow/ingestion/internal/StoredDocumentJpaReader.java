package com.docflow.ingestion.internal;

import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class StoredDocumentJpaReader implements StoredDocumentReader {

  private final StoredDocumentEntityRepository repository;

  StoredDocumentJpaReader(StoredDocumentEntityRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<StoredDocument> get(StoredDocumentId id) {
    return repository.findById(id.value()).map(StoredDocumentJpaReader::toRecord);
  }

  private static StoredDocument toRecord(StoredDocumentEntity entity) {
    return new StoredDocument(
        StoredDocumentId.of(entity.getId()),
        UUID.fromString(entity.getOrganizationId()),
        entity.getUploadedAt(),
        entity.getSourceFilename(),
        entity.getMimeType(),
        entity.getStoragePath());
  }
}
