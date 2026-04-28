package com.docflow.workflow;

public sealed interface WorkflowAction
    permits WorkflowAction.Approve,
        WorkflowAction.Reject,
        WorkflowAction.Flag,
        WorkflowAction.Resolve {

  record Approve() implements WorkflowAction {}

  record Reject() implements WorkflowAction {}

  record Flag(String comment) implements WorkflowAction {}

  record Resolve(String newDocTypeId) implements WorkflowAction {}
}
