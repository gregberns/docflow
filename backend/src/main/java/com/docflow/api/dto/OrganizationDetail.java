package com.docflow.api.dto;

import com.docflow.config.org.FieldDefinition;
import com.docflow.config.org.WorkflowDefinition;
import java.util.List;
import java.util.Map;

public record OrganizationDetail(
    String id,
    String name,
    String icon,
    List<String> docTypes,
    List<WorkflowDefinition> workflows,
    Map<String, List<FieldDefinition>> fieldSchemas) {}
