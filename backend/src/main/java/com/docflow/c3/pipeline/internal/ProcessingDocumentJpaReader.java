package com.docflow.c3.pipeline.internal;

import com.docflow.c3.pipeline.ProcessingDocument;
import com.docflow.c3.pipeline.ProcessingDocumentId;
import com.docflow.c3.pipeline.ProcessingDocumentReader;
import com.docflow.ingestion.StoredDocumentId;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class ProcessingDocumentJpaReader implements ProcessingDocumentReader {

  private final ProcessingDocumentEntityRepository repository;

  ProcessingDocumentJpaReader(ProcessingDocumentEntityRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<ProcessingDocument> get(ProcessingDocumentId id) {
    return repository.findById(id.value()).map(ProcessingDocumentJpaReader::toRecord);
  }

  private static ProcessingDocument toRecord(ProcessingDocumentEntity entity) {
    return new ProcessingDocument(
        ProcessingDocumentId.of(entity.getId()),
        StoredDocumentId.of(entity.getStoredDocumentId()),
        entity.getOrganizationId(),
        entity.getCurrentStep(),
        entity.getRawText(),
        entity.getLastError(),
        entity.getCreatedAt());
  }
}
