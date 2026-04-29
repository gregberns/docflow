package com.docflow.api.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.docflow.api.error.GlobalExceptionHandler;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.ReextractionStatus;
import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import com.docflow.workflow.WorkflowAction;
import com.docflow.workflow.WorkflowEngine;
import com.docflow.workflow.WorkflowError;
import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceReader;
import com.docflow.workflow.WorkflowOutcome;
import com.docflow.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class DocumentActionControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String ORG_ID = "org-alpha";
  private static final String DOC_TYPE_ID = "invoice";
  private static final String STAGE_ID = "manager-approval";
  private static final Instant UPLOADED_AT = Instant.parse("2026-04-27T12:00:00Z");
  private static final Instant PROCESSED_AT = Instant.parse("2026-04-27T12:00:30Z");

  private WorkflowEngine workflowEngine;
  private DocumentReader documentReader;
  private StoredDocumentReader storedDocumentReader;
  private WorkflowInstanceReader workflowInstanceReader;
  private WorkflowCatalog workflowCatalog;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    workflowEngine = mock(WorkflowEngine.class);
    documentReader = mock(DocumentReader.class);
    storedDocumentReader = mock(StoredDocumentReader.class);
    workflowInstanceReader = mock(WorkflowInstanceReader.class);
    workflowCatalog = mock(WorkflowCatalog.class);
    DocumentActionController controller =
        new DocumentActionController(
            workflowEngine,
            documentReader,
            storedDocumentReader,
            workflowInstanceReader,
            workflowCatalog);
    mockMvc = standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test
  void approve_invokesEngineOnceAndReturnsView() throws Exception {
    UUID documentId = UUID.randomUUID();
    seedHappyPath(documentId, WorkflowStatus.AWAITING_REVIEW);
    when(workflowEngine.applyAction(eq(documentId), any(WorkflowAction.class)))
        .thenReturn(new WorkflowOutcome.Success(currentInstance(documentId)));

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"Approve\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.currentStageId").value(STAGE_ID));

    ArgumentCaptor<WorkflowAction> captor = ArgumentCaptor.forClass(WorkflowAction.class);
    verify(workflowEngine, times(1)).applyAction(eq(documentId), captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue())
        .isInstanceOf(WorkflowAction.Approve.class);
  }

  @Test
  void reject_invokesEngineOnceWithRejectAction() throws Exception {
    UUID documentId = UUID.randomUUID();
    seedHappyPath(documentId, WorkflowStatus.AWAITING_REVIEW);
    when(workflowEngine.applyAction(eq(documentId), any(WorkflowAction.class)))
        .thenReturn(new WorkflowOutcome.Success(currentInstance(documentId)));

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"Reject\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<WorkflowAction> captor = ArgumentCaptor.forClass(WorkflowAction.class);
    verify(workflowEngine, times(1)).applyAction(eq(documentId), captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue())
        .isInstanceOf(WorkflowAction.Reject.class);
  }

  @Test
  void flag_invokesEngineOnceAndForwardsComment() throws Exception {
    UUID documentId = UUID.randomUUID();
    seedHappyPath(documentId, WorkflowStatus.FLAGGED);
    when(workflowEngine.applyAction(eq(documentId), any(WorkflowAction.class)))
        .thenReturn(new WorkflowOutcome.Success(currentInstance(documentId)));

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"Flag\",\"comment\":\"missing totals\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<WorkflowAction> captor = ArgumentCaptor.forClass(WorkflowAction.class);
    verify(workflowEngine, times(1)).applyAction(eq(documentId), captor.capture());
    WorkflowAction captured = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(captured).isInstanceOf(WorkflowAction.Flag.class);
    org.assertj.core.api.Assertions.assertThat(((WorkflowAction.Flag) captured).comment())
        .isEqualTo("missing totals");
  }

  @Test
  void resolve_invokesEngineOnceWithNullDocType() throws Exception {
    UUID documentId = UUID.randomUUID();
    seedHappyPath(documentId, WorkflowStatus.AWAITING_REVIEW);
    when(workflowEngine.applyAction(eq(documentId), any(WorkflowAction.class)))
        .thenReturn(new WorkflowOutcome.Success(currentInstance(documentId)));

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"Resolve\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<WorkflowAction> captor = ArgumentCaptor.forClass(WorkflowAction.class);
    verify(workflowEngine, times(1)).applyAction(eq(documentId), captor.capture());
    WorkflowAction captured = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(captured).isInstanceOf(WorkflowAction.Resolve.class);
    org.assertj.core.api.Assertions.assertThat(((WorkflowAction.Resolve) captured).newDocTypeId())
        .isNull();
  }

  @Test
  void flag_emptyComment_returns400ValidationFailed() throws Exception {
    UUID documentId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"Flag\",\"comment\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.details[0].path").value("comment"));

    verify(workflowEngine, never()).applyAction(any(), any());
  }

  @Test
  void approveOnFiled_invalidAction_returns409() throws Exception {
    UUID documentId = UUID.randomUUID();
    when(workflowEngine.applyAction(eq(documentId), any(WorkflowAction.class)))
        .thenReturn(
            new WorkflowOutcome.Failure(new WorkflowError.InvalidAction("filed", "APPROVE")));

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"Approve\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_ACTION"))
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void resolve_extractionInProgress_returns409Reextraction() throws Exception {
    UUID documentId = UUID.randomUUID();
    when(workflowEngine.applyAction(eq(documentId), any(WorkflowAction.class)))
        .thenReturn(
            new WorkflowOutcome.Failure(new WorkflowError.ExtractionInProgress(documentId)));

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"Resolve\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("REEXTRACTION_IN_PROGRESS"))
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void unknownDocument_returns404() throws Exception {
    UUID documentId = UUID.randomUUID();
    when(workflowEngine.applyAction(eq(documentId), any(WorkflowAction.class)))
        .thenReturn(new WorkflowOutcome.Failure(new WorkflowError.UnknownDocument(documentId)));

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"Approve\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("UNKNOWN_DOCUMENT"));
  }

  private void seedHappyPath(UUID documentId, WorkflowStatus status) {
    UUID storedUuid = UUID.randomUUID();
    StoredDocumentId storedId = StoredDocumentId.of(storedUuid);
    Document document =
        new Document(
            documentId,
            storedUuid,
            ORG_ID,
            DOC_TYPE_ID,
            Map.of("amount", 42),
            "raw text",
            PROCESSED_AT,
            ReextractionStatus.NONE);
    StoredDocument stored =
        new StoredDocument(
            storedId, ORG_ID, UPLOADED_AT, "invoice-001.pdf", "application/pdf", "/tmp/x");
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(), documentId, ORG_ID, STAGE_ID, status, null, null, UPLOADED_AT);
    WorkflowView workflow =
        new WorkflowView(
            ORG_ID,
            DOC_TYPE_ID,
            List.of(
                new StageView(STAGE_ID, "Manager Approval", "approval", "AWAITING_APPROVAL", null)),
            List.of());

    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(storedDocumentReader.get(any(StoredDocumentId.class))).thenReturn(Optional.of(stored));
    when(workflowInstanceReader.getByDocumentId(documentId)).thenReturn(Optional.of(instance));
    when(workflowCatalog.getWorkflow(ORG_ID, DOC_TYPE_ID)).thenReturn(Optional.of(workflow));
  }

  private WorkflowInstance currentInstance(UUID documentId) {
    return new WorkflowInstance(
        UUID.randomUUID(),
        documentId,
        ORG_ID,
        STAGE_ID,
        WorkflowStatus.AWAITING_REVIEW,
        null,
        null,
        UPLOADED_AT);
  }
}
