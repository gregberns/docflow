package com.docflow.api.error;

public enum ErrorCode {
  UNKNOWN_ORGANIZATION(404),
  UNKNOWN_DOCUMENT(404),
  UNKNOWN_PROCESSING_DOCUMENT(404),
  UNKNOWN_DOC_TYPE(404),
  UNSUPPORTED_MEDIA_TYPE(415),
  INVALID_FILE(400),
  VALIDATION_FAILED(400),
  INVALID_ACTION(409),
  REEXTRACTION_IN_PROGRESS(409),
  LLM_UNAVAILABLE(502),
  INTERNAL_ERROR(500);

  private final int httpStatus;

  ErrorCode(int httpStatus) {
    this.httpStatus = httpStatus;
  }

  public int httpStatus() {
    return httpStatus;
  }
}
