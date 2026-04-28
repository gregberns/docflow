package com.docflow.ingestion;

import java.time.Instant;

public record StoredDocument(
    StoredDocumentId id,
    String organizationId,
    Instant uploadedAt,
    String sourceFilename,
    String mimeType,
    String storagePath) {}
