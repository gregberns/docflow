package com.docflow.api.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.docflow.api.error.GlobalExceptionHandler;
import com.docflow.config.catalog.DocumentTypeCatalog;
import com.docflow.config.catalog.DocumentTypeSchemaView;
import com.docflow.config.catalog.FieldView;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.document.ReextractionStatus;
import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import com.docflow.workflow.WorkflowAction;
import com.docflow.workflow.WorkflowEngine;
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

class ReviewControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String ORG_ID = "org-alpha";
  private static final String DOC_TYPE_ID = "invoice";
  private static final String OTHER_DOC_TYPE_ID = "receipt";
  private static final String STAGE_ID = "manager-approval";
  private static final Instant UPLOADED_AT = Instant.parse("2026-04-27T12:00:00Z");
  private static final Instant PROCESSED_AT = Instant.parse("2026-04-27T12:00:30Z");

  private WorkflowEngine workflowEngine;
  private DocumentReader documentReader;
  private DocumentWriter documentWriter;
  private StoredDocumentReader storedDocumentReader;
  private WorkflowInstanceReader workflowInstanceReader;
  private WorkflowCatalog workflowCatalog;
  private DocumentTypeCatalog documentTypeCatalog;
  private OrganizationCatalog organizationCatalog;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    workflowEngine = mock(WorkflowEngine.class);
    documentReader = mock(DocumentReader.class);
    documentWriter = mock(DocumentWriter.class);
    storedDocumentReader = mock(StoredDocumentReader.class);
    workflowInstanceReader = mock(WorkflowInstanceReader.class);
    workflowCatalog = mock(WorkflowCatalog.class);
    documentTypeCatalog = mock(DocumentTypeCatalog.class);
    organizationCatalog = mock(OrganizationCatalog.class);
    ReviewController controller =
        new ReviewController(
            workflowEngine,
            documentReader,
            documentWriter,
            storedDocumentReader,
            workflowInstanceReader,
            workflowCatalog,
            documentTypeCatalog,
            organizationCatalog);
    mockMvc = standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test
  void patchFields_validShape_returns200WithUpdatedFields() throws Exception {
    UUID documentId = UUID.randomUUID();
    seedDocument(documentId, DOC_TYPE_ID, Map.of("amount", 42));
    seedSchema(
        DOC_TYPE_ID,
        List.of(
            new FieldView("amount", "DECIMAL", true, null, null),
            new FieldView("vendor", "STRING", false, null, null)));

    Map<String, Object> updatedFields = Map.of("amount", 99.5, "vendor", "ACME");
    when(documentReader.get(documentId))
        .thenReturn(Optional.of(buildDocument(documentId, DOC_TYPE_ID, Map.of("amount", 42))))
        .thenReturn(Optional.of(buildDocument(documentId, DOC_TYPE_ID, updatedFields)));

    mockMvc
        .perform(
            patch("/api/documents/" + documentId + "/review/fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"extractedFields\":{\"amount\":99.5,\"vendor\":\"ACME\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.extractedFields.amount").value(99.5))
        .andExpect(jsonPath("$.extractedFields.vendor").value("ACME"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(documentWriter, times(1))
        .updateExtraction(eq(documentId), eq(DOC_TYPE_ID), captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue())
        .containsEntry("vendor", "ACME")
        .containsKey("amount");
  }

  @Test
  void patchFields_missingRequired_returns400WithFieldDetails() throws Exception {
    UUID documentId = UUID.randomUUID();
    seedDocument(documentId, DOC_TYPE_ID, Map.of());
    seedSchema(
        DOC_TYPE_ID,
        List.of(
            new FieldView("amount", "DECIMAL", true, null, null),
            new FieldView("vendor", "STRING", true, null, null)));

    mockMvc
        .perform(
            patch("/api/documents/" + documentId + "/review/fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"extractedFields\":{\"amount\":99.5}}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.details[0].path").value("extractedFields.vendor"));

    verify(documentWriter, never()).updateExtraction(any(), any(), any());
  }

  @Test
  void patchFields_wrongType_returns400() throws Exception {
    UUID documentId = UUID.randomUUID();
    seedDocument(documentId, DOC_TYPE_ID, Map.of());
    seedSchema(DOC_TYPE_ID, List.of(new FieldView("amount", "DECIMAL", true, null, null)));

    mockMvc
        .perform(
            patch("/api/documents/" + documentId + "/review/fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"extractedFields\":{\"amount\":[1,2]}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details[0].path").value("extractedFields.amount"));

    verify(documentWriter, never()).updateExtraction(any(), any(), any());
  }

  @Test
  void patchFields_unknownDocument_returns404() throws Exception {
    UUID documentId = UUID.randomUUID();
    when(documentReader.get(documentId)).thenReturn(Optional.empty());

    mockMvc
        .perform(
            patch("/api/documents/" + documentId + "/review/fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"extractedFields\":{}}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("UNKNOWN_DOCUMENT"));
  }

  @Test
  void retype_validNewType_returns202AndCallsEngineResolveOnce() throws Exception {
    UUID documentId = UUID.randomUUID();
    seedDocument(documentId, DOC_TYPE_ID, Map.of());
    when(organizationCatalog.getAllowedDocTypes(ORG_ID))
        .thenReturn(List.of(DOC_TYPE_ID, OTHER_DOC_TYPE_ID));
    when(documentTypeCatalog.getDocumentTypeSchema(ORG_ID, OTHER_DOC_TYPE_ID))
        .thenReturn(
            Optional.of(
                new DocumentTypeSchemaView(
                    ORG_ID, OTHER_DOC_TYPE_ID, "Receipt", "TEXT", List.of())));
    when(workflowEngine.applyAction(eq(documentId), any(WorkflowAction.class)))
        .thenReturn(
            new WorkflowOutcome.Success(
                new WorkflowInstance(
                    UUID.randomUUID(),
                    documentId,
                    ORG_ID,
                    STAGE_ID,
                    WorkflowStatus.AWAITING_REVIEW,
                    null,
                    null,
                    UPLOADED_AT)));

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/review/retype")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newDocumentType\":\"" + OTHER_DOC_TYPE_ID + "\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.reextractionStatus").value("IN_PROGRESS"));

    ArgumentCaptor<WorkflowAction> captor = ArgumentCaptor.forClass(WorkflowAction.class);
    verify(workflowEngine, times(1)).applyAction(eq(documentId), captor.capture());
    WorkflowAction captured = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(captured).isInstanceOf(WorkflowAction.Resolve.class);
    org.assertj.core.api.Assertions.assertThat(((WorkflowAction.Resolve) captured).newDocTypeId())
        .isEqualTo(OTHER_DOC_TYPE_ID);
  }

  @Test
  void retype_unknownType_returns404UnknownDocType() throws Exception {
    UUID documentId = UUID.randomUUID();
    seedDocument(documentId, DOC_TYPE_ID, Map.of());
    when(organizationCatalog.getAllowedDocTypes(ORG_ID)).thenReturn(List.of(DOC_TYPE_ID));

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/review/retype")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newDocumentType\":\"bogus-type\"}"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_DOC_TYPE"))
        .andExpect(jsonPath("$.status").value(404));

    verify(workflowEngine, never()).applyAction(any(), any());
  }

  @Test
  void retype_blankNewType_returns400Validation() throws Exception {
    UUID documentId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/review/retype")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newDocumentType\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    verify(workflowEngine, never()).applyAction(any(), any());
  }

  @Test
  void retype_extractionInProgress_returns409Reextraction() throws Exception {
    UUID documentId = UUID.randomUUID();
    seedDocument(documentId, DOC_TYPE_ID, Map.of());
    when(organizationCatalog.getAllowedDocTypes(ORG_ID))
        .thenReturn(List.of(DOC_TYPE_ID, OTHER_DOC_TYPE_ID));
    when(documentTypeCatalog.getDocumentTypeSchema(ORG_ID, OTHER_DOC_TYPE_ID))
        .thenReturn(
            Optional.of(
                new DocumentTypeSchemaView(
                    ORG_ID, OTHER_DOC_TYPE_ID, "Receipt", "TEXT", List.of())));
    when(workflowEngine.applyAction(eq(documentId), any(WorkflowAction.class)))
        .thenReturn(
            new WorkflowOutcome.Failure(
                new com.docflow.workflow.WorkflowError.ExtractionInProgress(documentId)));

    mockMvc
        .perform(
            post("/api/documents/" + documentId + "/review/retype")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newDocumentType\":\"" + OTHER_DOC_TYPE_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REEXTRACTION_IN_PROGRESS"));
  }

  private Document buildDocument(UUID documentId, String docTypeId, Map<String, Object> fields) {
    return new Document(
        documentId,
        UUID.randomUUID(),
        ORG_ID,
        docTypeId,
        fields,
        "raw text",
        PROCESSED_AT,
        ReextractionStatus.NONE);
  }

  private void seedDocument(UUID documentId, String docTypeId, Map<String, Object> fields) {
    UUID storedUuid = UUID.randomUUID();
    StoredDocumentId storedId = StoredDocumentId.of(storedUuid);
    Document document = buildDocument(documentId, docTypeId, fields);
    StoredDocument stored =
        new StoredDocument(
            storedId, ORG_ID, UPLOADED_AT, "invoice-001.pdf", "application/pdf", "/tmp/x");
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(),
            documentId,
            ORG_ID,
            STAGE_ID,
            WorkflowStatus.AWAITING_REVIEW,
            null,
            null,
            UPLOADED_AT);
    WorkflowView workflow =
        new WorkflowView(
            ORG_ID,
            docTypeId,
            List.of(
                new StageView(STAGE_ID, "Manager Approval", "approval", "AWAITING_APPROVAL", null)),
            List.of());

    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(storedDocumentReader.get(any(StoredDocumentId.class))).thenReturn(Optional.of(stored));
    when(workflowInstanceReader.getByDocumentId(documentId)).thenReturn(Optional.of(instance));
    when(workflowCatalog.getWorkflow(ORG_ID, docTypeId)).thenReturn(Optional.of(workflow));
  }

  private void seedSchema(String docTypeId, List<FieldView> fields) {
    when(documentTypeCatalog.getDocumentTypeSchema(ORG_ID, docTypeId))
        .thenReturn(
            Optional.of(new DocumentTypeSchemaView(ORG_ID, docTypeId, "Doc Type", "TEXT", fields)));
  }
}
