package com.docflow.config.org;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record WorkflowDefinition(
    @NotBlank String organizationId,
    @NotBlank String documentTypeId,
    @NotEmpty @Valid List<StageDefinition> stages,
    @NotEmpty @Valid List<TransitionDefinition> transitions) {}
