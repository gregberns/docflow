package com.docflow.api.dto;

import com.docflow.document.ReextractionStatus;
import com.docflow.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DocumentView(
    UUID documentId,
    String organizationId,
    String sourceFilename,
    String mimeType,
    Instant uploadedAt,
    Instant processedAt,
    String rawText,
    String currentStageId,
    String currentStageDisplayName,
    WorkflowStatus currentStatus,
    String workflowOriginStage,
    String flagComment,
    String detectedDocumentType,
    Map<String, Object> extractedFields,
    ReextractionStatus reextractionStatus) {
  public DocumentView {
    extractedFields = extractedFields == null ? Map.of() : Map.copyOf(extractedFields);
  }
}
