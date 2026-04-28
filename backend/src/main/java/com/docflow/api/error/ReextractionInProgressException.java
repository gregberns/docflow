package com.docflow.api.error;

public final class ReextractionInProgressException extends DocflowException {

  public ReextractionInProgressException(String documentId) {
    super(
        ErrorCode.REEXTRACTION_IN_PROGRESS,
        "Re-extraction already in progress for document: " + documentId);
  }
}
