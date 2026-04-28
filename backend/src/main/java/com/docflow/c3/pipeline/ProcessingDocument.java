package com.docflow.c3.pipeline;

import com.docflow.ingestion.StoredDocumentId;
import java.time.Instant;

public record ProcessingDocument(
    ProcessingDocumentId id,
    StoredDocumentId storedDocumentId,
    String organizationId,
    String currentStep,
    String rawText,
    String lastError,
    Instant createdAt) {}
