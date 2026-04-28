package com.docflow.c3.events;

import com.docflow.platform.DocumentEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ExtractionCompleted(
    UUID documentId,
    String organizationId,
    Map<String, Object> extractedFields,
    String detectedDocumentType,
    Instant occurredAt)
    implements DocumentEvent {}
