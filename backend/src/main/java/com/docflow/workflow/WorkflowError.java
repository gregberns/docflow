package com.docflow.workflow;

import java.util.List;
import java.util.UUID;

public sealed interface WorkflowError
    permits WorkflowError.InvalidAction,
        WorkflowError.ValidationFailed,
        WorkflowError.UnknownDocument,
        WorkflowError.ExtractionInProgress {

  record InvalidAction(String currentStageId, String actionType) implements WorkflowError {}

  record ValidationFailed(List<ValidationDetail> details) implements WorkflowError {}

  record UnknownDocument(UUID documentId) implements WorkflowError {}

  record ExtractionInProgress(UUID documentId) implements WorkflowError {}

  record ValidationDetail(String path, String message) {}
}
