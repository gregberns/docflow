package com.docflow.api.error;

public final class InvalidFileException extends DocflowException {

  public InvalidFileException(String message) {
    super(ErrorCode.INVALID_FILE, message);
  }
}
