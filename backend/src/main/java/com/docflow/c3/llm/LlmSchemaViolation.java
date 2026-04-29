package com.docflow.c3.llm;

public final class LlmSchemaViolation extends LlmException {

  public LlmSchemaViolation(String message) {
    super(message);
  }

  public LlmSchemaViolation(String message, Throwable cause) {
    super(message, cause);
  }
}
