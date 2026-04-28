package com.docflow.c3.events;

import com.docflow.platform.DocumentEvent;
import java.time.Instant;
import java.util.UUID;

public record StoredDocumentIngested(
    UUID storedDocumentId, UUID organizationId, UUID processingDocumentId, Instant occurredAt)
    implements DocumentEvent {}
