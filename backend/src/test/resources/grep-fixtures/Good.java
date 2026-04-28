// Test fixture for GrepForbiddenStringsTest. Not compiled.
// Uses enum references instead of raw stage-name literals; the grep
// check must NOT flag this file.
package grepfixtures;

public final class Good {
  enum WorkflowStatus {
    AWAITING_REVIEW,
    FLAGGED,
    AWAITING_APPROVAL,
    FILED,
    REJECTED
  }

  public WorkflowStatus terminalFiled() {
    return WorkflowStatus.FILED;
  }

  public WorkflowStatus terminalRejected() {
    return WorkflowStatus.REJECTED;
  }
}
