package com.docflow.workflow;

public sealed interface WorkflowOutcome permits WorkflowOutcome.Success, WorkflowOutcome.Failure {

  record Success(WorkflowInstance instance) implements WorkflowOutcome {}

  record Failure(WorkflowError error) implements WorkflowOutcome {}
}
