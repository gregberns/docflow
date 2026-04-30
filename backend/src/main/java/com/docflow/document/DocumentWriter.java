package com.docflow.document;

import java.util.Map;
import java.util.UUID;

public interface DocumentWriter {

  void insert(Document document);

  void updateExtraction(UUID documentId, String detectedDocumentType, Map<String, Object> fields);

  void setReextractionStatus(UUID documentId, ReextractionStatus status);

  /**
   * Atomically claims the IN_PROGRESS slot for a re-extraction. Returns true if this caller won the
   * race and should proceed; false if a concurrent caller already holds the slot.
   */
  boolean claimReextractionInProgress(UUID documentId);
}
