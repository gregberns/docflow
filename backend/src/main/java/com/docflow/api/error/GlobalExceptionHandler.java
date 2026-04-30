package com.docflow.api.error;

import com.docflow.c3.llm.LlmException;
import com.docflow.c3.llm.LlmProtocolError;
import com.docflow.c3.llm.LlmSchemaViolation;
import com.docflow.c3.llm.LlmTimeout;
import com.docflow.c3.llm.LlmUnavailable;
import com.docflow.c3.llm.RetypeAlreadyInProgressException;
import com.docflow.ingestion.UnsupportedMediaTypeException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.InvalidTypeIdException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

  @ExceptionHandler(DocflowException.class)
  public ResponseEntity<ProblemDetail> handleDocflow(DocflowException ex) {
    return build(ex.code(), ex.getMessage(), toBodyDetails(ex.details()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleBeanValidation(MethodArgumentNotValidException ex) {
    List<DetailEntry> details =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new DetailEntry(fe.getField(), fe.getDefaultMessage()))
            .toList();
    return build(ErrorCode.VALIDATION_FAILED, "Request body validation failed", details);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException ex) {
    return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ex.getMessage(), List.of());
  }

  @ExceptionHandler(UnsupportedMediaTypeException.class)
  public ResponseEntity<ProblemDetail> handleIngestionUnsupportedMediaType(
      UnsupportedMediaTypeException ex) {
    return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ex.getMessage(), List.of());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
    Throwable cause = ex.getCause();
    if (cause instanceof InvalidTypeIdException polymorphismFailure) {
      String path = pathFromInvalidTypeId(polymorphismFailure);
      return build(
          ErrorCode.VALIDATION_FAILED,
          "action is required and must be one of: Approve, Reject, Flag, Resolve",
          List.of(
              new DetailEntry(
                  path, "unknown value; expected one of: Approve, Reject, Flag, Resolve")));
    }
    return build(ErrorCode.VALIDATION_FAILED, "Malformed request body", List.of());
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoResourceFound(NoResourceFoundException ex) {
    return build(ErrorCode.NOT_FOUND, "Resource not found", List.of());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handlePathTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    return build(
        ErrorCode.VALIDATION_FAILED,
        "Invalid path parameter",
        List.of(new DetailEntry(ex.getName(), "invalid value")));
  }

  @ExceptionHandler(RetypeAlreadyInProgressException.class)
  public ResponseEntity<ProblemDetail> handleRetypeAlreadyInProgress(
      RetypeAlreadyInProgressException ex) {
    return build(ErrorCode.REEXTRACTION_IN_PROGRESS, ex.getMessage(), List.of());
  }

  @ExceptionHandler(LlmException.class)
  public ResponseEntity<ProblemDetail> handleLlm(LlmException ex) {
    LOG.warn("LLM call surfaced to API boundary: {}", ex.getClass().getSimpleName());
    ErrorCode code = mapLlmErrorCode(ex);
    return build(code, ex.getMessage(), List.of());
  }

  private static ErrorCode mapLlmErrorCode(LlmException ex) {
    if (ex instanceof LlmTimeout) {
      return ErrorCode.LLM_TIMEOUT;
    }
    if (ex instanceof LlmUnavailable) {
      return ErrorCode.LLM_UNAVAILABLE;
    }
    if (ex instanceof LlmProtocolError) {
      return ErrorCode.LLM_PROTOCOL_ERROR;
    }
    if (ex instanceof LlmSchemaViolation) {
      return ErrorCode.LLM_SCHEMA_VIOLATION;
    }
    return ErrorCode.LLM_UNAVAILABLE;
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
    LOG.warn("Optimistic locking failure: {}", ex.getMessage());
    return build(
        ErrorCode.CONCURRENT_MODIFICATION,
        "Resource was modified concurrently; please retry",
        List.of());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUncaught(Exception ex) {
    LOG.error("Uncaught exception bubbled to GlobalExceptionHandler", ex);
    return build(ErrorCode.INTERNAL_ERROR, "Internal server error", List.of());
  }

  private ResponseEntity<ProblemDetail> build(
      ErrorCode code, String message, List<DetailEntry> details) {
    HttpStatusCode status = HttpStatus.valueOf(code.httpStatus());
    ProblemDetail body = ProblemDetail.forStatus(status);
    body.setProperty("code", code.name());
    body.setProperty("message", message == null ? code.name() : message);
    if (!details.isEmpty()) {
      body.setProperty("details", details);
    }
    return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(body);
  }

  private List<DetailEntry> toBodyDetails(List<DocflowException.FieldError> details) {
    if (details == null || details.isEmpty()) {
      return List.of();
    }
    return details.stream().map(d -> new DetailEntry(d.path(), d.message())).toList();
  }

  private String pathFromInvalidTypeId(InvalidTypeIdException ex) {
    var refs = ex.getPath();
    if (refs == null || refs.isEmpty()) {
      return "action";
    }
    StringBuilder sb = new StringBuilder();
    for (var ref : refs) {
      String name = ref.getPropertyName();
      if (name != null) {
        if (sb.length() > 0) {
          sb.append('.');
        }
        sb.append(name);
      }
    }
    return sb.length() == 0 ? "action" : sb.toString();
  }

  public record DetailEntry(String path, String message) {}
}
