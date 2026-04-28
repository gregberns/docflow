package com.docflow.api.error;

public final class UnknownDocumentException extends DocflowException {

  public UnknownDocumentException(String documentId) {
    super(ErrorCode.UNKNOWN_DOCUMENT, "Unknown document: " + documentId);
  }
}
