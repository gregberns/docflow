package com.docflow.api.dto;

import java.util.List;
import java.util.Map;

public record OrganizationDetail(
    String id,
    String name,
    String icon,
    List<String> docTypes,
    List<WorkflowSummary> workflows,
    Map<String, List<FieldSchema>> fieldSchemas) {}
