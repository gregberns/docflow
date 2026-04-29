package com.docflow.workflow;

import java.util.UUID;

public final class StaleWorkflowStateException extends RuntimeException {

  private final UUID documentId;
  private final String observedFromStageId;

  public StaleWorkflowStateException(UUID documentId, String observedFromStageId) {
    super(
        "workflow_instances row for document_id="
            + documentId
            + " no longer matches from-stage "
            + observedFromStageId);
    this.documentId = documentId;
    this.observedFromStageId = observedFromStageId;
  }

  public UUID documentId() {
    return documentId;
  }

  public String observedFromStageId() {
    return observedFromStageId;
  }
}
