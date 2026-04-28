package com.docflow.api.error;

public final class LlmUnavailableException extends DocflowException {

  public LlmUnavailableException(String message) {
    super(ErrorCode.LLM_UNAVAILABLE, message);
  }

  public LlmUnavailableException(String message, Throwable cause) {
    super(ErrorCode.LLM_UNAVAILABLE, message);
    initCause(cause);
  }
}
