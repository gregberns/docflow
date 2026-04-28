package com.docflow.api.error;

public final class UnknownDocTypeException extends DocflowException {

  public UnknownDocTypeException(String docTypeId) {
    super(ErrorCode.UNKNOWN_DOC_TYPE, "Unknown document type: " + docTypeId);
  }
}
