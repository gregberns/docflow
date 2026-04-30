package com.docflow.api.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.docflow.api.dto.ActionRequest;
import com.docflow.api.error.DocflowException.FieldError;
import com.docflow.c3.llm.LlmProtocolError;
import com.docflow.c3.llm.LlmSchemaViolation;
import com.docflow.c3.llm.LlmTimeout;
import com.docflow.c3.llm.LlmUnavailable;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerContractTest {

  private static final String PROBLEM_JSON = "application/problem+json";

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void unknownOrganization() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "unknownOrg"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_ORGANIZATION"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("org-x")))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void unknownDocument() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "unknownDoc"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_DOCUMENT"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void unknownProcessingDocument() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "unknownProcessingDoc"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_PROCESSING_DOCUMENT"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void unknownDocType() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "unknownDocType"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_DOC_TYPE"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void unsupportedMediaTypeFromSpring() throws Exception {
    mockMvc
        .perform(
            post("/test/echo")
                .contentType(MediaType.APPLICATION_XML)
                .content("<x/>".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
        .andExpect(jsonPath("$.status").value(415));
  }

  @Test
  void invalidFile() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "invalidFile"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_FILE"))
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void validationFailedFromDocflowException() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "validation"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.details[0].path").value("comment"))
        .andExpect(jsonPath("$.details[0].message").value("must be non-empty"));
  }

  @Test
  void validationFailedFromBeanValidationOnEmptyFlagComment() throws Exception {
    String body = "{\"action\":\"Flag\",\"comment\":\"\"}";
    mockMvc
        .perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.details[0].path").value("comment"));
  }

  @Test
  void validationFailedFromJacksonPolymorphismFailure() throws Exception {
    String body = "{\"action\":\"Bogus\"}";
    mockMvc
        .perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(
            jsonPath("$.message")
                .value("action is required and must be one of: Approve, Reject, Flag, Resolve"))
        .andExpect(jsonPath("$.details[0].path").value("action"))
        .andExpect(
            jsonPath("$.details[0].message")
                .value(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.docflow"))))
        .andExpect(
            jsonPath("$.details[0].message")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("subtype"))))
        .andExpect(
            jsonPath("$.details[0].message")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("type id"))));
  }

  @Test
  void validationFailedFromJacksonMissingDiscriminator() throws Exception {
    String body = "{}";
    mockMvc
        .perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(
            jsonPath("$.message")
                .value("action is required and must be one of: Approve, Reject, Flag, Resolve"))
        .andExpect(jsonPath("$.details[0].path").value("action"))
        .andExpect(
            jsonPath("$.details[0].message")
                .value(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.docflow"))))
        .andExpect(
            jsonPath("$.details[0].message")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("subtype"))))
        .andExpect(
            jsonPath("$.details[0].message")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("type id"))));
  }

  @Test
  void invalidAction() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "invalidAction"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_ACTION"))
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void reextractionInProgress() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "reextractionInProgress"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("REEXTRACTION_IN_PROGRESS"))
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void llmUnavailable() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "llmUnavailable"))
        .andExpect(status().isBadGateway())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
        .andExpect(jsonPath("$.status").value(502));
  }

  @Test
  void llmFamilyUnavailableMapsTo502() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "llmFamilyUnavailable"))
        .andExpect(status().isBadGateway())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
        .andExpect(jsonPath("$.status").value(502))
        .andExpect(jsonPath("$.message").value("LlmCall failed: LlmUnavailable"))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ACME"))));
  }

  @Test
  void llmFamilyTimeoutMapsTo504() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "llmFamilyTimeout"))
        .andExpect(status().isGatewayTimeout())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("LLM_TIMEOUT"))
        .andExpect(jsonPath("$.status").value(504))
        .andExpect(jsonPath("$.message").value("LlmCall failed: LlmTimeout"))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ACME"))));
  }

  @Test
  void llmFamilyProtocolMapsTo502() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "llmFamilyProtocol"))
        .andExpect(status().isBadGateway())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("LLM_PROTOCOL_ERROR"))
        .andExpect(jsonPath("$.status").value(502))
        .andExpect(jsonPath("$.message").value("LlmCall failed: LlmProtocolError"))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ACME"))));
  }

  @Test
  void llmFamilySchemaViolationMapsTo422() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "llmFamilySchema"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("LLM_SCHEMA_VIOLATION"))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.message").value("LlmCall failed: LlmSchemaViolation"))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ACME"))));
  }

  @Test
  void noResourceFoundReturns404() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "noResource"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("INTERNAL_ERROR")));
  }

  @Test
  void methodArgumentTypeMismatchReturns400() throws Exception {
    mockMvc
        .perform(post("/test/uuid/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("Invalid path parameter"))
        .andExpect(jsonPath("$.details[0].path").value("id"))
        .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("INTERNAL_ERROR")));
  }

  @Test
  void optimisticLockingFailureReturns409() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "optimisticLock"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("INTERNAL_ERROR")));
  }

  @Test
  void internalErrorFromCatchAll() throws Exception {
    mockMvc
        .perform(get("/test/throw").param("kind", "internal"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error"));
  }

  @RestController
  @RequestMapping("/test")
  static final class ThrowingController {

    @org.springframework.web.bind.annotation.GetMapping("/throw")
    public String throwIt(@RequestParam String kind) throws NoResourceFoundException {
      switch (kind) {
        case "unknownOrg" -> throw new UnknownOrganizationException("org-x");
        case "unknownDoc" -> throw new UnknownDocumentException("doc-x");
        case "unknownProcessingDoc" -> throw new UnknownProcessingDocumentException("pd-x");
        case "unknownDocType" -> throw new UnknownDocTypeException("type-x");
        case "invalidFile" -> throw new InvalidFileException("Empty upload");
        case "validation" ->
            throw new ValidationException(
                "Body invalid", List.of(new FieldError("comment", "must be non-empty")));
        case "invalidAction" -> throw new InvalidActionException("Action not allowed");
        case "reextractionInProgress" -> throw new ReextractionInProgressException("doc-x");
        case "llmUnavailable" -> throw new LlmUnavailableException("upstream 503");
        case "llmFamilyUnavailable" -> throw new LlmUnavailable("LlmCall failed: LlmUnavailable");
        case "llmFamilyTimeout" -> throw new LlmTimeout("LlmCall failed: LlmTimeout");
        case "llmFamilyProtocol" -> throw new LlmProtocolError("LlmCall failed: LlmProtocolError");
        case "llmFamilySchema" ->
            throw new LlmSchemaViolation("LlmCall failed: LlmSchemaViolation");
        case "optimisticLock" ->
            throw new OptimisticLockingFailureException(
                "workflow_instances row for document_id=abc changed during update");
        case "noResource" ->
            throw new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/health", "/api/health");
        case "internal" -> throw new IllegalStateException("boom");
        default -> throw new IllegalArgumentException("unknown kind: " + kind);
      }
    }

    @PostMapping(value = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ActionRequest echo(@Valid @RequestBody ActionRequest body) {
      return body;
    }

    @PostMapping("/uuid/{id}")
    public String uuidPath(@PathVariable UUID id) {
      return id.toString();
    }
  }
}
