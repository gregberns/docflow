package com.docflow.c3.events;

import com.docflow.platform.DocumentEvent;
import java.time.Instant;
import java.util.UUID;

public record ProcessingStepChanged(
    UUID storedDocumentId,
    UUID processingDocumentId,
    UUID organizationId,
    String currentStep,
    String error,
    Instant occurredAt)
    implements DocumentEvent {}
