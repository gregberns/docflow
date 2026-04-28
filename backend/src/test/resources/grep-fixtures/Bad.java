// Test fixture for GrepForbiddenStringsTest. Not compiled.
// Intentionally contains a forbidden raw stage-name literal so the
// grep-based check is proven to detect it.
package grepfixtures;

public final class Bad {
  public String stageName() {
    return "Manager Approval";
  }
}
