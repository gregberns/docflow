package com.docflow.api.error;

import java.util.List;

public abstract sealed class DocflowException extends RuntimeException
    permits UnknownOrganizationException,
        UnknownDocumentException,
        UnknownProcessingDocumentException,
        UnknownDocTypeException,
        InvalidFileException,
        ValidationException,
        InvalidActionException,
        ReextractionInProgressException,
        LlmUnavailableException {

  private final ErrorCode code;
  private final List<FieldError> details;

  protected DocflowException(ErrorCode code, String message) {
    this(code, message, List.of());
  }

  protected DocflowException(ErrorCode code, String message, List<FieldError> details) {
    super(message);
    this.code = code;
    this.details = List.copyOf(details);
  }

  public final ErrorCode code() {
    return code;
  }

  public final List<FieldError> details() {
    return details;
  }

  public record FieldError(String path, String message) {}
}
