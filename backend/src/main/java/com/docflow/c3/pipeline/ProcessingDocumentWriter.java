package com.docflow.c3.pipeline;

import com.docflow.ingestion.StoredDocumentId;
import java.time.Instant;

public interface ProcessingDocumentWriter {

  void insert(
      ProcessingDocumentId id,
      StoredDocumentId storedDocumentId,
      String organizationId,
      String currentStep,
      Instant createdAt);

  void updateStep(ProcessingDocumentId id, String currentStep);

  void updateRawText(ProcessingDocumentId id, String rawText);

  void markFailed(ProcessingDocumentId id, String lastError);
}
