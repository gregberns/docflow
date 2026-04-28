package com.docflow.workflow.events;

import com.docflow.platform.DocumentEvent;
import java.time.Instant;
import java.util.UUID;

public record DocumentStateChanged(
    UUID documentId,
    UUID storedDocumentId,
    UUID organizationId,
    String currentStage,
    String currentStatus,
    String reextractionStatus,
    String action,
    String comment,
    Instant occurredAt)
    implements DocumentEvent {}
