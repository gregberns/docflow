package com.docflow.config.catalog;

import java.util.List;

public record WorkflowView(
    String organizationId,
    String documentTypeId,
    List<StageView> stages,
    List<TransitionView> transitions) {

  public WorkflowView {
    stages = List.copyOf(stages);
    transitions = List.copyOf(transitions);
  }
}
