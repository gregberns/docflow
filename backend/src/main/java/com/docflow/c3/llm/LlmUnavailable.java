package com.docflow.c3.llm;

public final class LlmUnavailable extends LlmException {

  public LlmUnavailable(String message) {
    super(message);
  }

  public LlmUnavailable(String message, Throwable cause) {
    super(message, cause);
  }
}
