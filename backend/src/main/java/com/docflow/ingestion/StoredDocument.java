package com.docflow.ingestion;

import java.time.Instant;
import java.util.UUID;

public record StoredDocument(
    StoredDocumentId id,
    UUID organizationId,
    Instant uploadedAt,
    String sourceFilename,
    String mimeType,
    String storagePath) {}
