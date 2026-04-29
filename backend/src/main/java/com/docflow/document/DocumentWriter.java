package com.docflow.document;

import java.util.Map;
import java.util.UUID;

public interface DocumentWriter {

  void insert(Document document);

  void updateExtraction(UUID documentId, String detectedDocumentType, Map<String, Object> fields);

  void setReextractionStatus(UUID documentId, ReextractionStatus status);
}
