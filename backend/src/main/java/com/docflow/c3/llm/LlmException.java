package com.docflow.c3.llm;

public abstract sealed class LlmException extends RuntimeException
    permits LlmTimeout, LlmProtocolError, LlmSchemaViolation, LlmUnavailable {

  protected LlmException(String message) {
    super(message);
  }

  protected LlmException(String message, Throwable cause) {
    super(message, cause);
  }
}
