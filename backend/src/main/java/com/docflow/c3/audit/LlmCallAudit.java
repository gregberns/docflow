package com.docflow.c3.audit;

import com.docflow.ingestion.StoredDocumentId;
import java.time.Instant;
import java.util.UUID;

public record LlmCallAudit(
    LlmCallAuditId id,
    StoredDocumentId storedDocumentId,
    UUID processingDocumentId,
    UUID documentId,
    String organizationId,
    CallType callType,
    String modelId,
    String error,
    Instant at) {}
