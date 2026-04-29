package com.docflow.api.dto;

import java.util.List;

public record WorkflowSummary(
    String documentTypeId, List<StageSummary> stages, List<TransitionSummary> transitions) {}
