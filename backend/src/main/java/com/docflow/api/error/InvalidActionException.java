package com.docflow.api.error;

public final class InvalidActionException extends DocflowException {

  public InvalidActionException(String message) {
    super(ErrorCode.INVALID_ACTION, message);
  }
}
