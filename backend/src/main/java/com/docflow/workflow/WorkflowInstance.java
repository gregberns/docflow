package com.docflow.workflow;

import java.time.Instant;
import java.util.UUID;

public record WorkflowInstance(
    UUID id,
    UUID documentId,
    String organizationId,
    String currentStageId,
    WorkflowStatus currentStatus,
    String workflowOriginStage,
    String flagComment,
    Instant updatedAt) {}
