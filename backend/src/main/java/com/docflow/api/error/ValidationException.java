package com.docflow.api.error;

import java.util.List;

public final class ValidationException extends DocflowException {

  public ValidationException(String message) {
    super(ErrorCode.VALIDATION_FAILED, message);
  }

  public ValidationException(String message, List<FieldError> details) {
    super(ErrorCode.VALIDATION_FAILED, message, details);
  }
}
