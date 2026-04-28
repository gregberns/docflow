package com.docflow.config.org;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record OrgConfig(
    @NotNull @Valid List<OrganizationDefinition> organizations,
    @NotNull @Valid List<DocTypeDefinition> docTypes,
    @NotNull @Valid List<WorkflowDefinition> workflows) {}
