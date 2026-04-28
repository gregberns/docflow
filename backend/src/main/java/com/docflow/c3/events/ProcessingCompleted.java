package com.docflow.c3.events;

import com.docflow.platform.DocumentEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProcessingCompleted(
    UUID storedDocumentId,
    UUID processingDocumentId,
    UUID organizationId,
    String detectedDocumentType,
    Map<String, Object> extractedFields,
    String rawText,
    Instant occurredAt)
    implements DocumentEvent {}
