package com.docflow.workflow.events;

import com.docflow.platform.DocumentEvent;
import java.time.Instant;
import java.util.UUID;

public record RetypeRequested(
    UUID documentId, String organizationId, String newDocTypeId, Instant occurredAt)
    implements DocumentEvent {}
