package com.docflow.c3.llm;

public final class LlmTimeout extends LlmException {

  public LlmTimeout(String message) {
    super(message);
  }

  public LlmTimeout(String message, Throwable cause) {
    super(message, cause);
  }
}
