package com.docflow.config.org;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record DocTypeDefinition(
    @NotBlank String organizationId,
    @NotBlank String id,
    @NotBlank String displayName,
    @NotEmpty @Valid List<FieldDefinition> fields) {}
