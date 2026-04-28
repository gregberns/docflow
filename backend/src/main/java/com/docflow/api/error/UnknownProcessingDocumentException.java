package com.docflow.api.error;

public final class UnknownProcessingDocumentException extends DocflowException {

  public UnknownProcessingDocumentException(String processingDocumentId) {
    super(
        ErrorCode.UNKNOWN_PROCESSING_DOCUMENT,
        "Unknown processing document: " + processingDocumentId);
  }
}
