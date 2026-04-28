package com.docflow.config.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StageDefinition(
    @NotBlank String id,
    String displayName,
    @NotNull StageKind kind,
    @NotNull WorkflowStatus canonicalStatus,
    String role) {}
