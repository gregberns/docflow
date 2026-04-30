package com.docflow.api.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.docflow.ingestion.storage.StoredDocumentStorage;
import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceReader;
import com.docflow.workflow.WorkflowStatus;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class DocumentControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String ORG_ID = "org-alpha";
  private static final String DOC_TYPE_ID = "invoice";
  private static final String STAGE_ID = "manager-approval";
  private static final String STAGE_DISPLAY_NAME = "Manager Approval Step";
  private static final Instant UPLOADED_AT = Instant.parse("2026-04-27T12:00:00Z");
  private static final Instant PROCESSED_AT = Instant.parse("2026-04-27T12:00:30Z");

  private DocumentReader documentReader;
  private StoredDocumentReader storedDocumentReader;
  private WorkflowInstanceReader workflowInstanceReader;
  private WorkflowCatalog workflowCatalog;
  private StoredDocumentStorage storedDocumentStorage;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    documentReader = mock(DocumentReader.class);
    storedDocumentReader = mock(StoredDocumentReader.class);
    workflowInstanceReader = mock(WorkflowInstanceReader.class);
    workflowCatalog = mock(WorkflowCatalog.class);
    storedDocumentStorage = mock(StoredDocumentStorage.class);
    DocumentController controller =
        new DocumentController(
            documentReader,
            storedDocumentReader,
            workflowInstanceReader,
            workflowCatalog,
            storedDocumentStorage);
    mockMvc = standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test
  void getDocument_returnsDocumentViewWithStageDisplayName() throws Exception {
    UUID documentId = UUID.randomUUID();
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
            UUID.randomUUID(),
            documentId,
            ORG_ID,
            STAGE_ID,
            WorkflowStatus.AWAITING_APPROVAL,
            null,
            null,
            UPLOADED_AT);
    WorkflowView workflow =
        new WorkflowView(
            ORG_ID,
            DOC_TYPE_ID,
            List.of(
                new StageView("review", "Review Step", "review", "AWAITING_REVIEW", null),
                new StageView(
                    STAGE_ID, STAGE_DISPLAY_NAME, "approval", "AWAITING_APPROVAL", "manager")),
            List.of());

    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(storedDocumentReader.get(any(StoredDocumentId.class))).thenReturn(Optional.of(stored));
    when(workflowInstanceReader.getByDocumentId(documentId)).thenReturn(Optional.of(instance));
    when(workflowCatalog.getWorkflow(ORG_ID, DOC_TYPE_ID)).thenReturn(Optional.of(workflow));

    mockMvc
        .perform(get("/api/documents/" + documentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.organizationId").value(ORG_ID))
        .andExpect(jsonPath("$.sourceFilename").value("invoice-001.pdf"))
        .andExpect(jsonPath("$.mimeType").value("application/pdf"))
        .andExpect(jsonPath("$.currentStageId").value(STAGE_ID))
        .andExpect(jsonPath("$.currentStageDisplayName").value(STAGE_DISPLAY_NAME))
        .andExpect(jsonPath("$.currentStatus").value("AWAITING_APPROVAL"))
        .andExpect(jsonPath("$.detectedDocumentType").value(DOC_TYPE_ID))
        .andExpect(jsonPath("$.extractedFields.amount").value(42))
        .andExpect(jsonPath("$.reextractionStatus").value("NONE"));
  }

  @Test
  void getDocument_unknownId_returns404Problem() throws Exception {
    UUID documentId = UUID.randomUUID();
    when(documentReader.get(documentId)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/documents/" + documentId))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_DOCUMENT"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void getFile_streamsBytesWithCorrectContentType() throws Exception {
    UUID documentId = UUID.randomUUID();
    UUID storedUuid = UUID.randomUUID();
    StoredDocumentId storedId = StoredDocumentId.of(storedUuid);
    byte[] storedBytes = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    Document document =
        new Document(
            documentId,
            storedUuid,
            ORG_ID,
            DOC_TYPE_ID,
            Map.of(),
            null,
            PROCESSED_AT,
            ReextractionStatus.NONE);
    StoredDocument stored =
        new StoredDocument(storedId, ORG_ID, UPLOADED_AT, "scan.jpg", "image/jpeg", "/tmp/y");

    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(storedDocumentReader.get(any(StoredDocumentId.class))).thenReturn(Optional.of(stored));
    when(storedDocumentStorage.size(any(StoredDocumentId.class)))
        .thenReturn((long) storedBytes.length);
    when(storedDocumentStorage.openStream(any(StoredDocumentId.class)))
        .thenReturn(new ByteArrayInputStream(storedBytes));

    byte[] body =
        mockMvc
            .perform(get("/api/documents/" + documentId + "/file"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(header().longValue("Content-Length", storedBytes.length))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    org.assertj.core.api.Assertions.assertThat(body).containsExactly(storedBytes);
  }

  @Test
  void getFile_unknownId_returns404Problem() throws Exception {
    UUID documentId = UUID.randomUUID();
    when(documentReader.get(documentId)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/documents/" + documentId + "/file"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_DOCUMENT"))
        .andExpect(jsonPath("$.status").value(404));
  }
}
