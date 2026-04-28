package com.docflow.ingestion;

import java.util.UUID;

public record IngestionResult(UUID storedDocumentId, UUID processingDocumentId) {}
