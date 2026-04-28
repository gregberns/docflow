package com.docflow.c3.pipeline.internal;

import com.docflow.c3.pipeline.ProcessingDocumentId;
import com.docflow.c3.pipeline.ProcessingDocumentNotFoundException;
import com.docflow.c3.pipeline.ProcessingDocumentOrganizationMismatchException;
import com.docflow.c3.pipeline.ProcessingDocumentWriter;
import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ProcessingDocumentJpaWriter implements ProcessingDocumentWriter {

  private static final String FAILED_STEP = "FAILED";

  private final ProcessingDocumentEntityRepository repository;
  private final StoredDocumentReader storedDocumentReader;

  ProcessingDocumentJpaWriter(
      ProcessingDocumentEntityRepository repository, StoredDocumentReader storedDocumentReader) {
    this.repository = repository;
    this.storedDocumentReader = storedDocumentReader;
  }

  @Override
  @Transactional
  public void updateStep(ProcessingDocumentId id, String currentStep) {
    Objects.requireNonNull(currentStep, "currentStep");
    ProcessingDocumentEntity entity = loadAndAssertOrgConsistent(id);
    entity.setCurrentStep(currentStep);
    repository.save(entity);
  }

  @Override
  @Transactional
  public void updateRawText(ProcessingDocumentId id, String rawText) {
    ProcessingDocumentEntity entity = loadAndAssertOrgConsistent(id);
    entity.setRawText(rawText);
    repository.save(entity);
  }

  @Override
  @Transactional
  public void markFailed(ProcessingDocumentId id, String lastError) {
    ProcessingDocumentEntity entity = loadAndAssertOrgConsistent(id);
    entity.setCurrentStep(FAILED_STEP);
    entity.setLastError(lastError);
    repository.save(entity);
  }

  private ProcessingDocumentEntity loadAndAssertOrgConsistent(ProcessingDocumentId id) {
    ProcessingDocumentEntity entity =
        repository
            .findById(id.value())
            .orElseThrow(() -> new ProcessingDocumentNotFoundException(id));
    StoredDocument parent =
        storedDocumentReader
            .get(StoredDocumentId.of(entity.getStoredDocumentId()))
            .orElseThrow(() -> new ProcessingDocumentNotFoundException(id));
    if (!Objects.equals(entity.getOrganizationId(), parent.organizationId())) {
      throw new ProcessingDocumentOrganizationMismatchException(
          id, entity.getOrganizationId(), parent.organizationId());
    }
    return entity;
  }
}
