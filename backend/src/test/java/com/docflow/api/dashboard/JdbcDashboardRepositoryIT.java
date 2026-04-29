package com.docflow.api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.api.dto.DashboardStats;
import com.docflow.api.dto.DocumentView;
import com.docflow.api.dto.ProcessingItem;
import com.docflow.document.ReextractionStatus;
import com.docflow.workflow.WorkflowStatus;
import com.github.f4b6a3.uuid.UuidCreator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class JdbcDashboardRepositoryIT {

  private static final String ORG_A = "org-a";
  private static final String ORG_B = "org-b";
  private static final String DOC_TYPE = "doc-type";
  private static final String STAGE_REVIEW = "stage-rv";
  private static final String STAGE_REVIEW_DISPLAY = "rv-display";
  private static final String STAGE_APPROVAL = "stage-mgr";
  private static final String STAGE_FILED = "stage-fl";

  private static final Instant FIXED_NOW = Instant.parse("2026-04-29T12:00:00Z");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("db/migration/V1__init.sql"),
              "/docker-entrypoint-initdb.d/01-init.sql");

  private DataSource dataSource;
  private JdbcDashboardRepository repository;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    ds.setDriverClassName("org.postgresql.Driver");
    dataSource = ds;

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM workflow_instances");
    jdbc.update("DELETE FROM documents");
    jdbc.update("DELETE FROM processing_documents");
    jdbc.update("DELETE FROM stored_documents");
    jdbc.update("DELETE FROM transitions");
    jdbc.update("DELETE FROM stages");
    jdbc.update("DELETE FROM workflows");
    jdbc.update("DELETE FROM organization_doc_types");
    jdbc.update("DELETE FROM document_types");
    jdbc.update("DELETE FROM organizations");

    seedBaseConfig(jdbc);
    repository =
        new JdbcDashboardRepository(
            new NamedParameterJdbcTemplate(dataSource), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
  }

  @Test
  void listProcessing_returnsOnlyOrgARowsWithoutDocument() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID storedA = insertStoredDocument(jdbc, ORG_A, "a.pdf");
    UUID storedB = insertStoredDocument(jdbc, ORG_B, "b.pdf");
    UUID storedAlreadyDone = insertStoredDocument(jdbc, ORG_A, "done.pdf");

    UUID procA = insertProcessingDocument(jdbc, storedA, ORG_A, "CLASSIFYING", null, FIXED_NOW);
    insertProcessingDocument(jdbc, storedB, ORG_B, "CLASSIFYING", null, FIXED_NOW);
    UUID procDone =
        insertProcessingDocument(
            jdbc, storedAlreadyDone, ORG_A, "EXTRACTING", null, FIXED_NOW.minusSeconds(60));
    insertDocument(jdbc, storedAlreadyDone, ORG_A, FIXED_NOW.minusSeconds(30));

    List<ProcessingItem> processing = repository.listProcessing(ORG_A);

    assertThat(processing).extracting(ProcessingItem::processingDocumentId).containsExactly(procA);
    assertThat(processing).extracting(ProcessingItem::sourceFilename).containsExactly("a.pdf");
    assertThat(processing.get(0).currentStep()).isEqualTo("CLASSIFYING");
    assertThat(processing)
        .extracting(ProcessingItem::processingDocumentId)
        .doesNotContain(procDone);
  }

  @Test
  void listProcessing_orderedByCreatedAtDesc() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID stored1 = insertStoredDocument(jdbc, ORG_A, "old.pdf");
    UUID stored2 = insertStoredDocument(jdbc, ORG_A, "new.pdf");
    UUID procOld =
        insertProcessingDocument(
            jdbc, stored1, ORG_A, "CLASSIFYING", null, FIXED_NOW.minusSeconds(120));
    UUID procNew = insertProcessingDocument(jdbc, stored2, ORG_A, "EXTRACTING", null, FIXED_NOW);

    List<ProcessingItem> processing = repository.listProcessing(ORG_A);

    assertThat(processing)
        .extracting(ProcessingItem::processingDocumentId)
        .containsExactly(procNew, procOld);
  }

  @Test
  void listDocuments_returnsOrgADocumentsWithJoinedData() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID storedA = insertStoredDocument(jdbc, ORG_A, "doc-a.pdf");
    UUID storedB = insertStoredDocument(jdbc, ORG_B, "doc-b.pdf");
    UUID docA = insertDocument(jdbc, storedA, ORG_A, FIXED_NOW.minusSeconds(60));
    UUID docB = insertDocument(jdbc, storedB, ORG_B, FIXED_NOW.minusSeconds(60));
    insertWorkflowInstance(
        jdbc, docA, ORG_A, STAGE_REVIEW, WorkflowStatus.AWAITING_REVIEW, FIXED_NOW);
    insertWorkflowInstance(
        jdbc, docB, ORG_B, STAGE_REVIEW, WorkflowStatus.AWAITING_REVIEW, FIXED_NOW);

    List<DocumentView> docs = repository.listDocuments(ORG_A, Optional.empty(), Optional.empty());

    assertThat(docs).extracting(DocumentView::documentId).containsExactly(docA);
    DocumentView view = docs.get(0);
    assertThat(view.organizationId()).isEqualTo(ORG_A);
    assertThat(view.sourceFilename()).isEqualTo("doc-a.pdf");
    assertThat(view.currentStageId()).isEqualTo(STAGE_REVIEW);
    assertThat(view.currentStageDisplayName()).isEqualTo(STAGE_REVIEW_DISPLAY);
    assertThat(view.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_REVIEW);
    assertThat(view.detectedDocumentType()).isEqualTo(DOC_TYPE);
    assertThat(view.reextractionStatus()).isEqualTo(ReextractionStatus.NONE);
    assertThat(view.extractedFields()).containsEntry("vendor", "ACME");
  }

  @Test
  void listDocuments_orderedByUpdatedAtDesc() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID stored1 = insertStoredDocument(jdbc, ORG_A, "old.pdf");
    UUID stored2 = insertStoredDocument(jdbc, ORG_A, "new.pdf");
    UUID doc1 = insertDocument(jdbc, stored1, ORG_A, FIXED_NOW.minusSeconds(120));
    UUID doc2 = insertDocument(jdbc, stored2, ORG_A, FIXED_NOW.minusSeconds(60));
    insertWorkflowInstance(
        jdbc,
        doc1,
        ORG_A,
        STAGE_REVIEW,
        WorkflowStatus.AWAITING_REVIEW,
        FIXED_NOW.minusSeconds(120));
    insertWorkflowInstance(
        jdbc, doc2, ORG_A, STAGE_REVIEW, WorkflowStatus.AWAITING_REVIEW, FIXED_NOW);

    List<DocumentView> docs = repository.listDocuments(ORG_A, Optional.empty(), Optional.empty());

    assertThat(docs).extracting(DocumentView::documentId).containsExactly(doc2, doc1);
  }

  @Test
  void listDocuments_filtersByStatus() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID stored1 = insertStoredDocument(jdbc, ORG_A, "review.pdf");
    UUID stored2 = insertStoredDocument(jdbc, ORG_A, "filed.pdf");
    UUID doc1 = insertDocument(jdbc, stored1, ORG_A, FIXED_NOW.minusSeconds(120));
    UUID doc2 = insertDocument(jdbc, stored2, ORG_A, FIXED_NOW.minusSeconds(60));
    insertWorkflowInstance(
        jdbc, doc1, ORG_A, STAGE_REVIEW, WorkflowStatus.AWAITING_REVIEW, FIXED_NOW);
    insertWorkflowInstance(jdbc, doc2, ORG_A, STAGE_FILED, WorkflowStatus.FILED, FIXED_NOW);

    List<DocumentView> reviewing =
        repository.listDocuments(
            ORG_A, Optional.of(WorkflowStatus.AWAITING_REVIEW), Optional.empty());

    assertThat(reviewing).extracting(DocumentView::documentId).containsExactly(doc1);
  }

  @Test
  void listDocuments_filtersByDocType() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID stored1 = insertStoredDocument(jdbc, ORG_A, "doc1.pdf");
    UUID doc1 = insertDocument(jdbc, stored1, ORG_A, FIXED_NOW);
    insertWorkflowInstance(
        jdbc, doc1, ORG_A, STAGE_REVIEW, WorkflowStatus.AWAITING_REVIEW, FIXED_NOW);

    List<DocumentView> matching =
        repository.listDocuments(ORG_A, Optional.empty(), Optional.of(DOC_TYPE));
    List<DocumentView> nonMatching =
        repository.listDocuments(ORG_A, Optional.empty(), Optional.of("does-not-exist"));

    assertThat(matching).extracting(DocumentView::documentId).containsExactly(doc1);
    assertThat(nonMatching).isEmpty();
  }

  @Test
  void stats_aggregatesAcrossDataset() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID storedProc = insertStoredDocument(jdbc, ORG_A, "p.pdf");
    insertProcessingDocument(jdbc, storedProc, ORG_A, "CLASSIFYING", null, FIXED_NOW);

    UUID storedReview = insertStoredDocument(jdbc, ORG_A, "rv.pdf");
    UUID docReview = insertDocument(jdbc, storedReview, ORG_A, FIXED_NOW);
    insertWorkflowInstance(
        jdbc, docReview, ORG_A, STAGE_REVIEW, WorkflowStatus.AWAITING_REVIEW, FIXED_NOW);

    UUID storedFlag = insertStoredDocument(jdbc, ORG_A, "fl.pdf");
    UUID docFlag = insertDocument(jdbc, storedFlag, ORG_A, FIXED_NOW);
    insertWorkflowInstance(jdbc, docFlag, ORG_A, STAGE_REVIEW, WorkflowStatus.FLAGGED, FIXED_NOW);

    UUID storedFiledNow = insertStoredDocument(jdbc, ORG_A, "filed-now.pdf");
    UUID docFiledNow = insertDocument(jdbc, storedFiledNow, ORG_A, FIXED_NOW);
    Instant thisMonth =
        YearMonth.from(FIXED_NOW.atOffset(ZoneOffset.UTC))
            .atDay(5)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);
    insertWorkflowInstance(jdbc, docFiledNow, ORG_A, STAGE_FILED, WorkflowStatus.FILED, thisMonth);

    UUID storedFiledOld = insertStoredDocument(jdbc, ORG_A, "filed-old.pdf");
    UUID docFiledOld =
        insertDocument(jdbc, storedFiledOld, ORG_A, FIXED_NOW.minusSeconds(86400 * 60));
    Instant lastMonth =
        YearMonth.from(FIXED_NOW.atOffset(ZoneOffset.UTC))
            .minusMonths(1)
            .atDay(5)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);
    insertWorkflowInstance(jdbc, docFiledOld, ORG_A, STAGE_FILED, WorkflowStatus.FILED, lastMonth);

    UUID storedOtherOrg = insertStoredDocument(jdbc, ORG_B, "other.pdf");
    insertProcessingDocument(jdbc, storedOtherOrg, ORG_B, "CLASSIFYING", null, FIXED_NOW);

    DashboardStats stats = repository.stats(ORG_A);

    assertThat(stats.inProgress()).isEqualTo(1L);
    assertThat(stats.awaitingReview()).isEqualTo(1L);
    assertThat(stats.flagged()).isEqualTo(1L);
    assertThat(stats.filedThisMonth()).isEqualTo(1L);
  }

  private void seedBaseConfig(JdbcTemplate jdbc) {
    jdbc.update(
        "INSERT INTO organizations (id, display_name, icon_id, ordinal) VALUES (?, ?, ?, ?)",
        ORG_A,
        "Org A",
        "icon-a",
        0);
    jdbc.update(
        "INSERT INTO organizations (id, display_name, icon_id, ordinal) VALUES (?, ?, ?, ?)",
        ORG_B,
        "Org B",
        "icon-b",
        1);
    for (String orgId : List.of(ORG_A, ORG_B)) {
      jdbc.update(
          "INSERT INTO document_types (organization_id, id, display_name, field_schema) "
              + "VALUES (?, ?, ?, '{\"fields\": []}'::jsonb)",
          orgId,
          DOC_TYPE,
          "Doc Type");
      jdbc.update(
          "INSERT INTO organization_doc_types (organization_id, document_type_id, ordinal) "
              + "VALUES (?, ?, 0)",
          orgId,
          DOC_TYPE);
      jdbc.update(
          "INSERT INTO workflows (organization_id, document_type_id) VALUES (?, ?)",
          orgId,
          DOC_TYPE);
      jdbc.update(
          "INSERT INTO stages "
              + "(organization_id, document_type_id, id, display_name, kind, canonical_status, role, ordinal) "
              + "VALUES (?, ?, ?, ?, 'REVIEW', 'AWAITING_REVIEW', NULL, 0)",
          orgId,
          DOC_TYPE,
          STAGE_REVIEW,
          STAGE_REVIEW_DISPLAY);
      jdbc.update(
          "INSERT INTO stages "
              + "(organization_id, document_type_id, id, display_name, kind, canonical_status, role, ordinal) "
              + "VALUES (?, ?, ?, ?, 'APPROVAL', 'AWAITING_APPROVAL', 'manager', 1)",
          orgId,
          DOC_TYPE,
          STAGE_APPROVAL,
          "mgr-display");
      jdbc.update(
          "INSERT INTO stages "
              + "(organization_id, document_type_id, id, display_name, kind, canonical_status, role, ordinal) "
              + "VALUES (?, ?, ?, ?, 'TERMINAL', 'FILED', NULL, 2)",
          orgId,
          DOC_TYPE,
          STAGE_FILED,
          "fl-display");
    }
  }

  private static UUID insertStoredDocument(JdbcTemplate jdbc, String orgId, String filename) {
    UUID id = UuidCreator.getTimeOrderedEpoch();
    jdbc.update(
        "INSERT INTO stored_documents "
            + "(id, organization_id, uploaded_at, source_filename, mime_type, storage_path) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        id,
        orgId,
        Timestamp.from(FIXED_NOW.minusSeconds(300)),
        filename,
        "application/pdf",
        "/tmp/" + id + ".bin");
    return id;
  }

  private static UUID insertProcessingDocument(
      JdbcTemplate jdbc,
      UUID storedDocumentId,
      String orgId,
      String currentStep,
      String lastError,
      Instant createdAt) {
    UUID id = UuidCreator.getTimeOrderedEpoch();
    jdbc.update(
        "INSERT INTO processing_documents "
            + "(id, stored_document_id, organization_id, current_step, raw_text, last_error, created_at) "
            + "VALUES (?, ?, ?, ?, NULL, ?, ?)",
        id,
        storedDocumentId,
        orgId,
        currentStep,
        lastError,
        Timestamp.from(createdAt));
    return id;
  }

  private static UUID insertDocument(
      JdbcTemplate jdbc, UUID storedDocumentId, String orgId, Instant processedAt) {
    UUID id = UuidCreator.getTimeOrderedEpoch();
    jdbc.update(
        "INSERT INTO documents "
            + "(id, stored_document_id, organization_id, detected_document_type, "
            + "extracted_fields, raw_text, processed_at, reextraction_status) "
            + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 'NONE')",
        id,
        storedDocumentId,
        orgId,
        DOC_TYPE,
        "{\"vendor\":\"ACME\"}",
        "raw text",
        Timestamp.from(processedAt));
    return id;
  }

  private static void insertWorkflowInstance(
      JdbcTemplate jdbc,
      UUID documentId,
      String orgId,
      String stageId,
      WorkflowStatus status,
      Instant updatedAt) {
    UUID id = UuidCreator.getTimeOrderedEpoch();
    jdbc.update(
        "INSERT INTO workflow_instances "
            + "(id, document_id, organization_id, document_type_id, current_stage_id, "
            + "current_status, workflow_origin_stage, flag_comment, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
        id,
        documentId,
        orgId,
        DOC_TYPE,
        stageId,
        status.name(),
        Timestamp.from(updatedAt));
  }
}
