package com.docflow.c3.pipeline;

import com.docflow.ingestion.StoredDocumentId;
import java.util.Map;

final class PipelineContext {

  private final ProcessingDocumentId processingDocumentId;
  private final StoredDocumentId storedDocumentId;
  private final String organizationId;
  private String rawText;
  private String detectedDocumentType;
  private Map<String, Object> extractedFields;

  PipelineContext(
      ProcessingDocumentId processingDocumentId,
      StoredDocumentId storedDocumentId,
      String organizationId) {
    this.processingDocumentId = processingDocumentId;
    this.storedDocumentId = storedDocumentId;
    this.organizationId = organizationId;
  }

  ProcessingDocumentId processingDocumentId() {
    return processingDocumentId;
  }

  StoredDocumentId storedDocumentId() {
    return storedDocumentId;
  }

  String organizationId() {
    return organizationId;
  }

  String rawText() {
    return rawText;
  }

  void setRawText(String rawText) {
    this.rawText = rawText;
  }

  String detectedDocumentType() {
    return detectedDocumentType;
  }

  void setDetectedDocumentType(String detectedDocumentType) {
    this.detectedDocumentType = detectedDocumentType;
  }

  Map<String, Object> extractedFields() {
    return extractedFields;
  }

  void setExtractedFields(Map<String, Object> extractedFields) {
    this.extractedFields = extractedFields;
  }
}
