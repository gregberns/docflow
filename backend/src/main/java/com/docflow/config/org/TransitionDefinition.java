package com.docflow.config.org;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TransitionDefinition(
    @NotBlank String from,
    @NotNull TransitionAction action,
    @NotBlank String to,
    @Valid StageGuardConfig guard) {}
