package com.docflow.api.error;

public final class UnknownOrganizationException extends DocflowException {

  public UnknownOrganizationException(String orgId) {
    super(ErrorCode.UNKNOWN_ORGANIZATION, "Unknown organization: " + orgId);
  }
}
