package com.docflow.c3.llm;

public final class LlmProtocolError extends LlmException {

  public LlmProtocolError(String message) {
    super(message);
  }

  public LlmProtocolError(String message, Throwable cause) {
    super(message, cause);
  }
}
