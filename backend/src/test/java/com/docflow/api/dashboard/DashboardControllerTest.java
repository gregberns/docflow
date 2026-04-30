package com.docflow.api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.docflow.api.dto.DashboardStats;
import com.docflow.api.dto.DocumentCursor;
import com.docflow.api.dto.DocumentView;
import com.docflow.api.dto.DocumentsPage;
import com.docflow.api.dto.ProcessingItem;
import com.docflow.api.error.GlobalExceptionHandler;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.config.catalog.OrganizationView;
import com.docflow.document.ReextractionStatus;
import com.docflow.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

class DashboardControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String KNOWN_ORG = "pinnacle-legal";

  private OrganizationCatalog organizationCatalog;
  private DashboardRepository dashboardRepository;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    organizationCatalog = Mockito.mock(OrganizationCatalog.class);
    dashboardRepository = Mockito.mock(DashboardRepository.class);

    OrganizationView orgView =
        new OrganizationView(KNOWN_ORG, "Pinnacle Legal Group", "icon", List.of("invoice"));
    when(organizationCatalog.getOrganization(KNOWN_ORG)).thenReturn(Optional.of(orgView));
    when(organizationCatalog.getOrganization("does-not-exist")).thenReturn(Optional.empty());

    when(dashboardRepository.listProcessing(any())).thenReturn(List.of());
    when(dashboardRepository.listDocumentsPage(any(), any(), any(), any(), any(), anyInt()))
        .thenReturn(new DocumentsPage(List.of(), null));
    when(dashboardRepository.stats(any())).thenReturn(new DashboardStats(0L, 0L, 0L, 0L));

    mockMvc =
        standaloneSetup(new DashboardController(organizationCatalog, dashboardRepository))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void happyPath_returnsThreeKeyShape() throws Exception {
    UUID processingId = UUID.randomUUID();
    UUID storedId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    Instant now = Instant.parse("2026-04-27T12:00:00Z");

    when(dashboardRepository.listProcessing(KNOWN_ORG))
        .thenReturn(
            List.of(
                new ProcessingItem(
                    processingId, storedId, "invoice-001.pdf", "CLASSIFYING", null, now)));
    when(dashboardRepository.listDocumentsPage(eq(KNOWN_ORG), any(), any(), any(), any(), anyInt()))
        .thenReturn(
            new DocumentsPage(
                List.of(
                    new DocumentView(
                        documentId,
                        KNOWN_ORG,
                        "invoice-001.pdf",
                        "application/pdf",
                        now,
                        now,
                        "raw text",
                        "review",
                        "Review",
                        WorkflowStatus.AWAITING_REVIEW,
                        null,
                        null,
                        "invoice",
                        Map.of(),
                        ReextractionStatus.NONE)),
                null));
    when(dashboardRepository.stats(KNOWN_ORG)).thenReturn(new DashboardStats(3L, 5L, 1L, 12L));

    mockMvc
        .perform(get("/api/organizations/{orgId}/documents", KNOWN_ORG))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processing").isArray())
        .andExpect(jsonPath("$.processing.length()").value(1))
        .andExpect(jsonPath("$.processing[0].processingDocumentId").value(processingId.toString()))
        .andExpect(jsonPath("$.processing[0].currentStep").value("CLASSIFYING"))
        .andExpect(jsonPath("$.documents").isArray())
        .andExpect(jsonPath("$.documents.length()").value(1))
        .andExpect(jsonPath("$.documents[0].documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.documents[0].currentStatus").value("AWAITING_REVIEW"))
        .andExpect(jsonPath("$.stats.inProgress").value(3))
        .andExpect(jsonPath("$.stats.awaitingReview").value(5))
        .andExpect(jsonPath("$.stats.flagged").value(1))
        .andExpect(jsonPath("$.stats.filedThisMonth").value(12))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void unknownOrg_returns404Problem() throws Exception {
    mockMvc
        .perform(get("/api/organizations/{orgId}/documents", "does-not-exist"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_ORGANIZATION"))
        .andExpect(jsonPath("$.status").value(404));

    verify(dashboardRepository, never()).listProcessing(any());
    verify(dashboardRepository, never())
        .listDocumentsPage(any(), any(), any(), any(), any(), anyInt());
    verify(dashboardRepository, never()).stats(any());
  }

  @Test
  void statusFilter_isPassedToListDocumentsOnly() throws Exception {
    mockMvc
        .perform(
            get("/api/organizations/{orgId}/documents", KNOWN_ORG)
                .param("status", "AWAITING_REVIEW"))
        .andExpect(status().isOk());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<WorkflowStatus>> statusCaptor = ArgumentCaptor.forClass(Optional.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<String>> stageCaptor = ArgumentCaptor.forClass(Optional.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<String>> docTypeCaptor = ArgumentCaptor.forClass(Optional.class);

    verify(dashboardRepository)
        .listDocumentsPage(
            eq(KNOWN_ORG),
            statusCaptor.capture(),
            stageCaptor.capture(),
            docTypeCaptor.capture(),
            any(),
            anyInt());
    assertThat(statusCaptor.getValue()).contains(WorkflowStatus.AWAITING_REVIEW);
    assertThat(stageCaptor.getValue()).isEmpty();
    assertThat(docTypeCaptor.getValue()).isEmpty();

    verify(dashboardRepository).listProcessing(KNOWN_ORG);
    verify(dashboardRepository).stats(KNOWN_ORG);
  }

  @Test
  void invalidStatus_returns400ValidationFailed() throws Exception {
    mockMvc
        .perform(get("/api/organizations/{orgId}/documents", KNOWN_ORG).param("status", "BOGUS"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.details[0].path").value("status"));

    verify(dashboardRepository, never())
        .listDocumentsPage(any(), any(), any(), any(), any(), anyInt());
  }

  @Test
  void docTypeFilter_isPassedToListDocuments() throws Exception {
    mockMvc
        .perform(get("/api/organizations/{orgId}/documents", KNOWN_ORG).param("docType", "invoice"))
        .andExpect(status().isOk());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<WorkflowStatus>> statusCaptor = ArgumentCaptor.forClass(Optional.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<String>> stageCaptor = ArgumentCaptor.forClass(Optional.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<String>> docTypeCaptor = ArgumentCaptor.forClass(Optional.class);

    verify(dashboardRepository)
        .listDocumentsPage(
            eq(KNOWN_ORG),
            statusCaptor.capture(),
            stageCaptor.capture(),
            docTypeCaptor.capture(),
            any(),
            anyInt());
    assertThat(statusCaptor.getValue()).isEmpty();
    assertThat(stageCaptor.getValue()).isEmpty();
    assertThat(docTypeCaptor.getValue()).contains("invoice");
  }

  @Test
  void combinedFilters_arePassedThroughToListDocumentsOnly() throws Exception {
    mockMvc
        .perform(
            get("/api/organizations/{orgId}/documents", KNOWN_ORG)
                .param("status", "FLAGGED")
                .param("docType", "invoice"))
        .andExpect(status().isOk());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<WorkflowStatus>> statusCaptor = ArgumentCaptor.forClass(Optional.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<String>> stageCaptor = ArgumentCaptor.forClass(Optional.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<String>> docTypeCaptor = ArgumentCaptor.forClass(Optional.class);

    verify(dashboardRepository)
        .listDocumentsPage(
            eq(KNOWN_ORG),
            statusCaptor.capture(),
            stageCaptor.capture(),
            docTypeCaptor.capture(),
            any(),
            anyInt());
    assertThat(statusCaptor.getValue()).contains(WorkflowStatus.FLAGGED);
    assertThat(stageCaptor.getValue()).isEmpty();
    assertThat(docTypeCaptor.getValue()).contains("invoice");

    verify(dashboardRepository).listProcessing(KNOWN_ORG);
    verify(dashboardRepository).stats(KNOWN_ORG);
  }

  @Test
  void stageFilter_isPassedToListDocuments() throws Exception {
    mockMvc
        .perform(get("/api/organizations/{orgId}/documents", KNOWN_ORG).param("stage", "Review"))
        .andExpect(status().isOk());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<WorkflowStatus>> statusCaptor = ArgumentCaptor.forClass(Optional.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<String>> stageCaptor = ArgumentCaptor.forClass(Optional.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<String>> docTypeCaptor = ArgumentCaptor.forClass(Optional.class);

    verify(dashboardRepository)
        .listDocumentsPage(
            eq(KNOWN_ORG),
            statusCaptor.capture(),
            stageCaptor.capture(),
            docTypeCaptor.capture(),
            any(),
            anyInt());
    assertThat(statusCaptor.getValue()).isEmpty();
    assertThat(stageCaptor.getValue()).contains("Review");
    assertThat(docTypeCaptor.getValue()).isEmpty();
  }

  @Test
  void cursorParams_arePassedToListDocumentsPage() throws Exception {
    Instant cursorTs = Instant.parse("2026-04-27T12:00:00Z");
    UUID cursorId = UUID.randomUUID();
    DocumentCursor next =
        new DocumentCursor(Instant.parse("2026-04-26T11:00:00Z"), UUID.randomUUID());
    when(dashboardRepository.listDocumentsPage(any(), any(), any(), any(), any(), anyInt()))
        .thenReturn(new DocumentsPage(List.of(), next));

    mockMvc
        .perform(
            get("/api/organizations/{orgId}/documents", KNOWN_ORG)
                .param("cursorUpdatedAt", cursorTs.toString())
                .param("cursorId", cursorId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor.updatedAt").value(next.updatedAt().toString()))
        .andExpect(jsonPath("$.nextCursor.id").value(next.id().toString()));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<DocumentCursor>> cursorCaptor = ArgumentCaptor.forClass(Optional.class);
    verify(dashboardRepository)
        .listDocumentsPage(eq(KNOWN_ORG), any(), any(), any(), cursorCaptor.capture(), anyInt());
    assertThat(cursorCaptor.getValue()).isPresent();
    assertThat(cursorCaptor.getValue().get().updatedAt()).isEqualTo(cursorTs);
    assertThat(cursorCaptor.getValue().get().id()).isEqualTo(cursorId);
  }

  @Test
  void cursorParam_partial_returns400() throws Exception {
    mockMvc
        .perform(
            get("/api/organizations/{orgId}/documents", KNOWN_ORG)
                .param("cursorUpdatedAt", "2026-04-27T12:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void cursorParam_invalidFormat_returns400() throws Exception {
    mockMvc
        .perform(
            get("/api/organizations/{orgId}/documents", KNOWN_ORG)
                .param("cursorUpdatedAt", "not-a-timestamp")
                .param("cursorId", UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
}
