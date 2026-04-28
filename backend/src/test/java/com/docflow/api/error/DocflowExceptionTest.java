package com.docflow.api.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docflow.api.error.DocflowException.FieldError;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocflowExceptionTest {

  @Test
  void unknownOrganizationCarriesCode() {
    UnknownOrganizationException ex = new UnknownOrganizationException("pinnacle-legal");
    assertSame(ErrorCode.UNKNOWN_ORGANIZATION, ex.code());
    assertTrue(ex.getMessage().contains("pinnacle-legal"));
    assertEquals(List.of(), ex.details());
  }

  @Test
  void unknownDocumentCarriesCode() {
    UnknownDocumentException ex = new UnknownDocumentException("doc-1");
    assertSame(ErrorCode.UNKNOWN_DOCUMENT, ex.code());
    assertTrue(ex.getMessage().contains("doc-1"));
  }

  @Test
  void validationCarriesDetails() {
    FieldError detail = new FieldError("comment", "must be non-empty");
    ValidationException ex = new ValidationException("invalid body", List.of(detail));
    assertSame(ErrorCode.VALIDATION_FAILED, ex.code());
    assertEquals(1, ex.details().size());
    assertEquals("comment", ex.details().getFirst().path());
    assertEquals("must be non-empty", ex.details().getFirst().message());
  }

  @Test
  void validationWithoutDetails() {
    ValidationException ex = new ValidationException("invalid body");
    assertSame(ErrorCode.VALIDATION_FAILED, ex.code());
    assertEquals(List.of(), ex.details());
  }

  @Test
  void invalidActionCarriesCode() {
    InvalidActionException ex = new InvalidActionException("not allowed in stage");
    assertSame(ErrorCode.INVALID_ACTION, ex.code());
    assertEquals("not allowed in stage", ex.getMessage());
  }

  @Test
  void reextractionInProgressCarriesCode() {
    ReextractionInProgressException ex = new ReextractionInProgressException("doc-1");
    assertSame(ErrorCode.REEXTRACTION_IN_PROGRESS, ex.code());
    assertTrue(ex.getMessage().contains("doc-1"));
  }

  @Test
  void llmUnavailableCarriesCode() {
    LlmUnavailableException ex = new LlmUnavailableException("anthropic 503");
    assertSame(ErrorCode.LLM_UNAVAILABLE, ex.code());
    assertEquals("anthropic 503", ex.getMessage());
  }

  @Test
  void llmUnavailableCarriesCause() {
    Throwable cause = new RuntimeException("upstream");
    LlmUnavailableException ex = new LlmUnavailableException("anthropic timeout", cause);
    assertSame(ErrorCode.LLM_UNAVAILABLE, ex.code());
    assertSame(cause, ex.getCause());
    assertNotNull(ex.getMessage());
  }
}
