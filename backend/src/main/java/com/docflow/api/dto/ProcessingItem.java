package com.docflow.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ProcessingItem(
    UUID processingDocumentId,
    UUID storedDocumentId,
    String sourceFilename,
    String currentStep,
    String lastError,
    Instant createdAt) {}
