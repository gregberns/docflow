package com.docflow.api.dto;

import java.util.UUID;

public record UploadAccepted(UUID storedDocumentId, UUID processingDocumentId) {}
