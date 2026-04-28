package com.docflow.config.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record OrganizationDefinition(
    @NotBlank String id,
    @NotBlank String displayName,
    @NotBlank String iconId,
    @NotEmpty List<String> documentTypeIds) {}
