package com.docflow.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.docflow.api.dto.UploadAccepted;
import com.docflow.c3.events.ProcessingStepChanged;
import com.docflow.c3.llm.LlmExtractor;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceReader;
import com.docflow.workflow.WorkflowStatus;
import com.docflow.workflow.events.DocumentStateChanged;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

class ScenarioRunnerIT extends AbstractScenarioIT {

  private static final Path FIXTURE_DIR =
      Paths.get("src/test/resources/scenarios").toAbsolutePath();
  private static final String ACTION_RETYPE = "RETYPE";
  private static final int HTTP_BAD_REQUEST = 400;

  @Autowired private ScenarioContext scenarioContext;
  @Autowired private ScenarioRecorder scenarioRecorder;
  @Autowired private DocumentReader documentReader;
  @Autowired private WorkflowInstanceReader workflowInstanceReader;
  @Autowired private DataSource dataSource;
  @Autowired private LlmExtractor llmExtractor;

  @LocalServerPort private int port;

  private final RestTemplate restTemplate = new RestTemplate();

  static Stream<ScenarioFixture> scenarios() {
    return new ScenarioFixtureLoader().loadAll(FIXTURE_DIR).stream();
  }

  @BeforeEach
  void resetScenarioState() {
    scenarioContext.clear();
    scenarioRecorder.reset();
    if (llmExtractor instanceof ScenarioLlmExtractorStub stub) {
      stub.resetInvocationCounts();
    }
  }

  @AfterEach
  void clearActiveFixture() {
    scenarioContext.clear();
  }

  @Test
  void contextLoadsUnderScenarioProfile() {
    assertThat(scenarioContext).isNotNull();
    assertThat(scenarioRecorder).isNotNull();
  }

  @TestFactory
  Stream<DynamicTest> runScenarios() {
    return scenarios()
        .map(fixture -> DynamicTest.dynamicTest(fixture.scenarioId(), () -> runScenario(fixture)));
  }

  private void runScenario(ScenarioFixture fixture) throws IOException {
    resetScenarioState();
    truncateDataTables();

    scenarioContext.setActive(fixture);

    byte[] pdfBytes = readPdf(fixture.inputPdf());
    UploadAccepted uploaded =
        uploadDocument(fixture.organizationId(), fixture.inputPdf(), pdfBytes);
    UUID processingDocumentId = uploaded.processingDocumentId();
    UUID storedDocumentId = uploaded.storedDocumentId();

    boolean expectFailure = expectsPipelineFailure(fixture);
    if (expectFailure) {
      awaitProcessingTerminal(processingDocumentId, "FAILED");
      assertFailureScenario(fixture, storedDocumentId, processingDocumentId);
      assertAuditRowCount(storedDocumentId, expectedAuditRowCount(fixture));
      return;
    }

    UUID documentId = awaitDocumentReachesReview(storedDocumentId);
    applyActions(fixture.actions(), documentId);
    awaitRetypeCompletion(fixture, documentId);
    assertSuccessScenario(fixture, documentId);
    assertAuditRowCount(storedDocumentId, expectedAuditRowCount(fixture));
    assertExtractInvocationCount(fixture);
  }

  private void awaitRetypeCompletion(ScenarioFixture fixture, UUID documentId) {
    List<ScenarioFixture.Action> actions = fixture.actions();
    if (actions == null) {
      return;
    }
    boolean hasRetype =
        actions.stream()
            .anyMatch(a -> ACTION_RETYPE.equalsIgnoreCase(a.type()) && successfullyAccepted(a));
    if (!hasRetype) {
      return;
    }
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () ->
                documentReader
                    .get(documentId)
                    .map(d -> d.reextractionStatus().name())
                    .filter(name -> !name.equals("IN_PROGRESS"))
                    .isPresent());
  }

  private boolean successfullyAccepted(ScenarioFixture.Action action) {
    int expected = action.expectedStatus() != null ? action.expectedStatus() : 202;
    return expected < 400;
  }

  private boolean expectsPipelineFailure(ScenarioFixture fixture) {
    return fixture.expectedEndState() != null && fixture.expectedEndState().document() == null;
  }

  private int expectedAuditRowCount(ScenarioFixture fixture) {
    if (!expectsPipelineFailure(fixture)) {
      int count = 2;
      List<ScenarioFixture.Action> actions = fixture.actions();
      if (actions != null) {
        for (ScenarioFixture.Action action : actions) {
          if (!ACTION_RETYPE.equalsIgnoreCase(action.type())) {
            continue;
          }
          int status = action.expectedStatus() != null ? action.expectedStatus() : 202;
          count += status >= 500 ? 2 : 1;
        }
      }
      return count;
    }
    if (!expectedEventsInclude(fixture, "ProcessingStepChanged", "CLASSIFYING")) {
      return 0;
    }
    if (fixture.extraction() != null && fixture.extraction().error() != null) {
      return 3;
    }
    return 1;
  }

  private void assertExtractInvocationCount(ScenarioFixture fixture) {
    if (!(llmExtractor instanceof ScenarioLlmExtractorStub stub)) {
      return;
    }
    int expected = 0;
    List<ScenarioFixture.Action> actions = fixture.actions();
    if (actions != null) {
      for (ScenarioFixture.Action action : actions) {
        if (ACTION_RETYPE.equalsIgnoreCase(action.type())) {
          expected += 1;
        }
      }
    }
    assertThat(stub.extractInvocationCount())
        .as("LlmExtractor.extract invocation count for " + fixture.scenarioId())
        .isEqualTo(expected);
  }

  private boolean expectedEventsInclude(ScenarioFixture fixture, String type, String currentStep) {
    if (fixture.expectedEndState() == null || fixture.expectedEndState().events() == null) {
      return false;
    }
    return fixture.expectedEndState().events().stream()
        .anyMatch(e -> type.equals(e.type()) && currentStep.equals(e.currentStep()));
  }

  private byte[] readPdf(String inputPdf) throws IOException {
    return ScenarioContext.tryResolve(inputPdf)
        .map(
            path -> {
              try {
                return Files.readAllBytes(path);
              } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
            })
        .orElseThrow(() -> new IOException("could not resolve fixture PDF: " + inputPdf));
  }

  private UploadAccepted uploadDocument(String orgId, String inputPdf, byte[] pdfBytes) {
    String filename = Paths.get(inputPdf).getFileName().toString();

    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.APPLICATION_PDF);
    fileHeaders.setContentDispositionFormData("file", filename);
    HttpEntity<ByteArrayResource> filePart =
        new HttpEntity<>(
            new ByteArrayResource(pdfBytes) {
              @Override
              public String getFilename() {
                return filename;
              }
            },
            fileHeaders);

    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("file", filePart);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(form, headers);

    ResponseEntity<UploadAccepted> response =
        restTemplate.exchange(
            url("/api/organizations/{orgId}/documents"),
            HttpMethod.POST,
            request,
            UploadAccepted.class,
            orgId);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UploadAccepted body = response.getBody();
    assertThat(body).isNotNull();
    return body;
  }

  private UUID awaitDocumentReachesReview(UUID storedDocumentId) {
    return await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> findReviewDocument(storedDocumentId), v -> v != null);
  }

  private UUID findReviewDocument(UUID storedDocumentId) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT d.id FROM documents d "
                + "JOIN workflow_instances w ON w.document_id = d.id "
                + "WHERE d.stored_document_id = ?::uuid "
                + "AND w.current_status = 'AWAITING_REVIEW'",
            storedDocumentId.toString());
    if (rows.isEmpty()) {
      return null;
    }
    Object id = rows.get(0).get("id");
    return id instanceof UUID u ? u : UUID.fromString(id.toString());
  }

  private void awaitProcessingTerminal(UUID processingDocumentId, String terminalStep) {
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> hasProcessingStep(processingDocumentId, terminalStep));
  }

  private boolean hasProcessingStep(UUID processingDocumentId, String step) {
    return scenarioRecorder.processingSteps().stream()
        .anyMatch(
            ps ->
                processingDocumentId.equals(ps.processingDocumentId())
                    && step.equals(ps.currentStep()));
  }

  private void applyActions(List<ScenarioFixture.Action> actions, UUID documentId) {
    if (actions == null) {
      return;
    }
    for (ScenarioFixture.Action action : actions) {
      applyAction(action, documentId);
    }
  }

  private void applyAction(ScenarioFixture.Action action, UUID documentId) {
    String type = action.type().toUpperCase(Locale.ROOT);
    if (ACTION_RETYPE.equals(type)) {
      applyRetypeAction(action, documentId);
      return;
    }
    String body = buildActionBody(action);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> request = new HttpEntity<>(body, headers);
    int expected = action.expectedStatus() != null ? action.expectedStatus() : 200;

    try {
      ResponseEntity<String> response =
          restTemplate.exchange(
              url("/api/documents/{documentId}/actions"),
              HttpMethod.POST,
              request,
              String.class,
              documentId);
      assertThat(response.getStatusCode().value())
          .as("action " + action.type() + " status code")
          .isEqualTo(expected);
    } catch (org.springframework.web.client.HttpStatusCodeException e) {
      assertThat(e.getStatusCode().value())
          .as("action " + action.type() + " status code")
          .isEqualTo(expected);
      if (expected >= HTTP_BAD_REQUEST) {
        assertProblemDetailBody(action, e);
      }
    }
  }

  private void applyRetypeAction(ScenarioFixture.Action action, UUID documentId) {
    if (action.newDocTypeId() == null || action.newDocTypeId().isBlank()) {
      throw new IllegalArgumentException("RETYPE action requires newDocTypeId");
    }
    String body = "{\"newDocumentType\":\"" + action.newDocTypeId() + "\"}";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> request = new HttpEntity<>(body, headers);
    int expected = action.expectedStatus() != null ? action.expectedStatus() : 202;

    try {
      ResponseEntity<String> response =
          restTemplate.exchange(
              url("/api/documents/{documentId}/review/retype"),
              HttpMethod.POST,
              request,
              String.class,
              documentId);
      assertThat(response.getStatusCode().value())
          .as("action RETYPE status code")
          .isEqualTo(expected);
    } catch (org.springframework.web.client.HttpStatusCodeException e) {
      assertThat(e.getStatusCode().value()).as("action RETYPE status code").isEqualTo(expected);
    }
  }

  private void assertProblemDetailBody(
      ScenarioFixture.Action action, org.springframework.web.client.HttpStatusCodeException e) {
    org.springframework.http.HttpHeaders responseHeaders = e.getResponseHeaders();
    MediaType contentType = responseHeaders == null ? null : responseHeaders.getContentType();
    assertThat(contentType).as("action " + action.type() + " response content-type").isNotNull();
    assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .as("action " + action.type() + " content-type is application/problem+json")
        .isTrue();
    String responseBody = e.getResponseBodyAsString();
    assertThat(responseBody).as("action " + action.type() + " response body").isNotBlank();
    java.util.Map<String, Object> parsed;
    try {
      parsed =
          tools.jackson.databind.json.JsonMapper.builder()
              .build()
              .readValue(
                  responseBody,
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
    } catch (tools.jackson.core.JacksonException jex) {
      throw new AssertionError("could not parse problem-detail body: " + responseBody, jex);
    }
    assertThat(parsed).containsKey("status");
    assertThat(((Number) parsed.get("status")).intValue())
        .as("problem-detail status field")
        .isEqualTo(e.getStatusCode().value());
    assertThat(parsed).containsKey("code");
    String expectedCode = expectedErrorCode(e.getStatusCode().value());
    assertThat(parsed.get("code")).as("problem-detail code field").isEqualTo(expectedCode);
  }

  private String expectedErrorCode(int httpStatus) {
    return switch (httpStatus) {
      case 409 -> "INVALID_ACTION";
      case 400 -> "VALIDATION_FAILED";
      case 404 -> "UNKNOWN_DOCUMENT";
      default -> null;
    };
  }

  private String buildActionBody(ScenarioFixture.Action action) {
    String type = action.type().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "APPROVE" -> "{\"action\":\"Approve\"}";
      case "REJECT" -> "{\"action\":\"Reject\"}";
      case "FLAG" ->
          "{\"action\":\"Flag\",\"comment\":\""
              + (action.comment() == null ? "flagged by scenario" : action.comment())
              + "\"}";
      case "RESOLVE" -> "{\"action\":\"Resolve\"}";
      default -> throw new IllegalArgumentException("unsupported action type: " + action.type());
    };
  }

  private void assertSuccessScenario(ScenarioFixture fixture, UUID documentId) {
    ScenarioFixture.ExpectedEndState end = fixture.expectedEndState();
    if (end == null) {
      return;
    }

    if (end.document() != null) {
      Document document =
          documentReader
              .get(documentId)
              .orElseThrow(() -> new AssertionError("document not found: " + documentId));
      assertThat(document.detectedDocumentType()).isEqualTo(end.document().detectedDocumentType());
      assertThat(document.reextractionStatus().name())
          .isEqualTo(end.document().reextractionStatus());
      if (end.document().extractedFieldsContains() != null) {
        end.document()
            .extractedFieldsContains()
            .forEach(
                (k, v) ->
                    assertThat(document.extractedFields())
                        .as("extracted field " + k)
                        .containsEntry(k, v));
      }
    }

    if (end.workflowInstance() != null) {
      WorkflowInstance instance =
          workflowInstanceReader
              .getByDocumentId(documentId)
              .orElseThrow(() -> new AssertionError("workflow instance not found: " + documentId));
      assertThat(instance.currentStageId()).isEqualTo(end.workflowInstance().currentStageId());
      assertThat(instance.currentStatus())
          .isEqualTo(WorkflowStatus.valueOf(end.workflowInstance().currentStatus()));
      assertThat(instance.workflowOriginStage())
          .isEqualTo(end.workflowInstance().workflowOriginStage());
      assertThat(instance.flagComment()).isEqualTo(end.workflowInstance().flagComment());
    }

    if (end.events() != null) {
      List<Object> recordedDocumentEvents = scenarioRecorder.eventsFor(documentId);
      List<ProcessingStepChanged> processingSteps = scenarioRecorder.processingSteps();
      assertEventsContainInOrder(end.events(), processingSteps, recordedDocumentEvents);
    }
  }

  private void assertFailureScenario(
      ScenarioFixture fixture, UUID storedDocumentId, UUID processingDocumentId) {
    ScenarioFixture.ExpectedEndState end = fixture.expectedEndState();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Long docCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM documents WHERE stored_document_id = ?::uuid",
            Long.class,
            storedDocumentId.toString());
    assertThat(docCount).as("documents row count for failed pipeline").isZero();

    Long workflowCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM workflow_instances w "
                + "JOIN documents d ON d.id = w.document_id "
                + "WHERE d.stored_document_id = ?::uuid",
            Long.class,
            storedDocumentId.toString());
    assertThat(workflowCount).as("workflow_instances row count for failed pipeline").isZero();

    String currentStep =
        jdbc.queryForObject(
            "SELECT current_step FROM processing_documents WHERE id = ?::uuid",
            String.class,
            processingDocumentId.toString());
    assertThat(currentStep).as("processing_documents.current_step").isEqualTo("FAILED");

    String lastError =
        jdbc.queryForObject(
            "SELECT last_error FROM processing_documents WHERE id = ?::uuid",
            String.class,
            processingDocumentId.toString());
    assertThat(lastError).as("processing_documents.last_error").isNotBlank();

    if (end != null && end.events() != null) {
      List<ProcessingStepChanged> processingSteps = scenarioRecorder.processingSteps();
      assertEventsContainInOrder(end.events(), processingSteps, List.of());
    }
  }

  private void assertEventsContainInOrder(
      List<ScenarioFixture.EventExpectation> expectations,
      List<ProcessingStepChanged> processingSteps,
      List<Object> documentEvents) {
    List<Object> combined =
        Stream.concat(processingSteps.stream(), documentEvents.stream())
            .map(o -> (Object) o)
            .toList();

    int cursor = 0;
    for (ScenarioFixture.EventExpectation expectation : expectations) {
      boolean matched = false;
      for (int i = cursor; i < combined.size(); i++) {
        if (matchesEvent(combined.get(i), expectation)) {
          cursor = i + 1;
          matched = true;
          break;
        }
      }
      if (!matched) {
        throw new AssertionError(
            "expected event not found in recorded sequence (order-preserving): " + expectation);
      }
    }
  }

  private boolean matchesEvent(Object event, ScenarioFixture.EventExpectation expectation) {
    String simple = event.getClass().getSimpleName();
    if (expectation.type() != null && !expectation.type().equals(simple)) {
      return false;
    }
    if (event instanceof ProcessingStepChanged psc) {
      if (expectation.currentStep() != null
          && !expectation.currentStep().equals(psc.currentStep())) {
        return false;
      }
    }
    if (event instanceof DocumentStateChanged dsc) {
      if (expectation.currentStage() != null
          && !expectation.currentStage().equals(dsc.currentStage())) {
        return false;
      }
      if (expectation.currentStatus() != null
          && !expectation.currentStatus().equals(dsc.currentStatus())) {
        return false;
      }
      if (expectation.reextractionStatus() != null
          && !expectation.reextractionStatus().equals(dsc.reextractionStatus())) {
        return false;
      }
      if (expectation.action() != null && !expectation.action().equals(dsc.action())) {
        return false;
      }
    }
    return true;
  }

  private void assertAuditRowCount(UUID storedDocumentId, int expected) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM llm_call_audit WHERE stored_document_id = ?::uuid",
            Long.class,
            storedDocumentId.toString());
    assertThat(count).as("llm_call_audit row count").isEqualTo(expected);
  }

  private void truncateDataTables() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        "TRUNCATE llm_call_audit, workflow_instances, documents, processing_documents,"
            + " stored_documents RESTART IDENTITY CASCADE");
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
