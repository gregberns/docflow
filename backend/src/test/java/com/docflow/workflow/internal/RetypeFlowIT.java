package com.docflow.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.docflow.c3.events.ExtractionCompleted;
import com.docflow.c3.events.ExtractionFailed;
import com.docflow.c3.llm.LlmExtractor;
import com.docflow.config.AppConfig;
import com.docflow.config.catalog.DocumentTypeCatalogImpl;
import com.docflow.config.catalog.OrganizationCatalogImpl;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowCatalogImpl;
import com.docflow.config.org.loader.ConfigLoader;
import com.docflow.config.org.seeder.OrgConfigSeedWriter;
import com.docflow.config.org.seeder.OrgConfigSeeder;
import com.docflow.config.org.validation.ConfigValidator;
import com.docflow.config.persistence.OrganizationRepository;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.document.ReextractionStatus;
import com.docflow.document.internal.JdbcDocumentReader;
import com.docflow.document.internal.JdbcDocumentWriter;
import com.docflow.platform.AsyncConfig;
import com.docflow.platform.DocumentEventBus;
import com.docflow.platform.TimeConfig;
import com.docflow.workflow.WorkflowAction;
import com.docflow.workflow.WorkflowEngine;
import com.docflow.workflow.WorkflowInstanceReader;
import com.docflow.workflow.WorkflowInstanceWriter;
import com.docflow.workflow.WorkflowStatus;
import com.docflow.workflow.events.DocumentStateChanged;
import com.github.f4b6a3.uuid.UuidCreator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    classes = RetypeFlowIT.RetypeFlowIntegrationApp.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none",
      "spring.threads.virtual.enabled=true",
      "docflow.llm.model-id=claude-sonnet-4-6",
      "docflow.llm.api-key=sk-ant-test",
      "docflow.llm.request-timeout=PT60S",
      "docflow.llm.eval.report-path=eval/reports/latest.md",
      "docflow.storage.storage-root=/tmp/docflow",
      "docflow.database.url=ignored",
      "docflow.database.user=ignored",
      "docflow.database.password=ignored",
      "docflow.config.seed-on-boot=false",
      "docflow.config.seed-resource-path=classpath:seed/"
    })
class RetypeFlowIT {

  private static final String ORG_ID = "test-org";
  private static final String OLD_DOC_TYPE_ID = "invoice";
  private static final String NEW_DOC_TYPE_ID = "receipt";
  private static final String STAGE_REVIEW_INV = "stage-rv-inv";
  private static final String STAGE_REVIEW_REC = "stage-rv-rec";
  private static final String STAGE_MANAGER_INV = "stage-mg-inv";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withInitScripts("db/migration/V1__init.sql", "fixtures/retype-flow-listener-seed.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private DataSource dataSource;
  @Autowired private DocumentStateChangedRecorder recorder;
  @Autowired private WorkflowEngine workflowEngine;
  @Autowired private LlmExtractor llmExtractor;
  @Autowired private FailingDocumentWriter failingDocumentWriter;

  private ListAppender<ILoggingEvent> listenerAppender;

  @BeforeEach
  void setUp() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM workflow_instances");
    jdbc.update("DELETE FROM documents");
    jdbc.update("DELETE FROM stored_documents");
    recorder.reset();
    failingDocumentWriter.reset();
    org.mockito.Mockito.reset(llmExtractor);

    listenerAppender = new ListAppender<>();
    listenerAppender.start();
    Logger listenerLogger = (Logger) LoggerFactory.getLogger(ExtractionEventListener.class);
    listenerLogger.addAppender(listenerAppender);
    listenerLogger.setLevel(Level.INFO);
  }

  @AfterEach
  void tearDown() {
    Logger listenerLogger = (Logger) LoggerFactory.getLogger(ExtractionEventListener.class);
    listenerLogger.detachAppender(listenerAppender);
    listenerAppender.stop();
  }

  @Test
  void resolveWithTypeChange_extractionCompleted_updatesDocAndPublishesNoneEvent() {
    UUID storedDocumentId = insertStoredDocument();
    UUID documentId =
        seedFlaggedDocument(storedDocumentId, ReextractionStatus.NONE, OLD_DOC_TYPE_ID);

    Map<String, Object> newFields = new LinkedHashMap<>();
    newFields.put("merchant", "ACME Cafe");
    newFields.put("total", "12.34");

    doAnswer(
            (InvocationOnMock inv) -> {
              UUID docId = inv.getArgument(0);
              new JdbcTemplate(dataSource)
                  .update(
                      "UPDATE documents SET reextraction_status = 'IN_PROGRESS' WHERE id = ?",
                      docId);
              return null;
            })
        .when(llmExtractor)
        .extract(eq(documentId), eq(NEW_DOC_TYPE_ID));

    workflowEngine.applyAction(documentId, new WorkflowAction.Resolve(NEW_DOC_TYPE_ID));

    DocumentStateChanged inProgress =
        awaitEventWithReextractionStatus(ReextractionStatus.IN_PROGRESS);
    assertThat(inProgress.documentId()).isEqualTo(documentId);
    assertThat(inProgress.storedDocumentId()).isEqualTo(storedDocumentId);
    assertThat(inProgress.organizationId()).isEqualTo(ORG_ID);
    assertThat(inProgress.action()).isEqualTo("RESOLVE");
    assertThat(inProgress.currentStage()).isEqualTo(STAGE_REVIEW_INV);
    assertThat(inProgress.currentStatus()).isEqualTo(WorkflowStatus.FLAGGED.name());

    verify(llmExtractor, times(1)).extract(eq(documentId), eq(NEW_DOC_TYPE_ID));

    eventPublisher.publishEvent(
        new ExtractionCompleted(
            documentId, ORG_ID, newFields, NEW_DOC_TYPE_ID, Instant.parse("2026-04-29T10:00:00Z")));

    DocumentStateChanged completed = awaitEventWithReextractionStatus(ReextractionStatus.NONE);
    assertThat(completed.documentId()).isEqualTo(documentId);
    assertThat(completed.currentStage()).isEqualTo(STAGE_REVIEW_REC);
    assertThat(completed.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_REVIEW.name());
    assertThat(completed.action()).isNull();
    assertThat(completed.comment()).isNull();

    assertExtractionCompletedPersisted(documentId);
  }

  private DocumentStateChanged awaitEventWithReextractionStatus(ReextractionStatus expected) {
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                recorder.events().stream()
                    .anyMatch(e -> expected.name().equals(e.reextractionStatus())));
    return recorder.events().stream()
        .filter(e -> expected.name().equals(e.reextractionStatus()))
        .findFirst()
        .orElseThrow();
  }

  private void assertExtractionCompletedPersisted(UUID documentId) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Map<String, Object> docRow =
        jdbc.queryForMap(
            "SELECT detected_document_type, reextraction_status FROM documents WHERE id = ?",
            documentId);
    assertThat(docRow.get("detected_document_type")).isEqualTo(NEW_DOC_TYPE_ID);
    assertThat(docRow.get("reextraction_status")).isEqualTo("NONE");
    String fieldsJson =
        jdbc.queryForObject(
            "SELECT extracted_fields::text FROM documents WHERE id = ?", String.class, documentId);
    assertThat(fieldsJson).contains("\"merchant\"").contains("\"ACME Cafe\"");

    Map<String, Object> wiRow =
        jdbc.queryForMap(
            "SELECT current_stage_id, current_status, workflow_origin_stage, flag_comment,"
                + " document_type_id FROM workflow_instances WHERE document_id = ?",
            documentId);
    assertThat(wiRow.get("current_stage_id")).isEqualTo(STAGE_REVIEW_REC);
    assertThat(wiRow.get("current_status")).isEqualTo(WorkflowStatus.AWAITING_REVIEW.name());
    assertThat(wiRow.get("workflow_origin_stage")).isNull();
    assertThat(wiRow.get("flag_comment")).isNull();
    assertThat(wiRow.get("document_type_id")).isEqualTo(NEW_DOC_TYPE_ID);
  }

  @Test
  void resolveWithTypeChange_unflaggedReview_extractionCompleted_updatesDocAndWorkflowInstance() {
    UUID storedDocumentId = insertStoredDocument();
    UUID documentId =
        seedUnflaggedReviewDocument(storedDocumentId, ReextractionStatus.NONE, OLD_DOC_TYPE_ID);

    Map<String, Object> newFields = new LinkedHashMap<>();
    newFields.put("merchant", "Bagel Hut");
    newFields.put("total", "7.50");

    doAnswer(
            (InvocationOnMock inv) -> {
              UUID docId = inv.getArgument(0);
              new JdbcTemplate(dataSource)
                  .update(
                      "UPDATE documents SET reextraction_status = 'IN_PROGRESS' WHERE id = ?",
                      docId);
              return null;
            })
        .when(llmExtractor)
        .extract(eq(documentId), eq(NEW_DOC_TYPE_ID));

    workflowEngine.applyAction(documentId, new WorkflowAction.Resolve(NEW_DOC_TYPE_ID));

    DocumentStateChanged inProgress =
        awaitEventWithReextractionStatus(ReextractionStatus.IN_PROGRESS);
    assertThat(inProgress.documentId()).isEqualTo(documentId);
    assertThat(inProgress.organizationId()).isEqualTo(ORG_ID);
    assertThat(inProgress.action()).isEqualTo("RESOLVE");
    assertThat(inProgress.currentStage()).isEqualTo(STAGE_REVIEW_INV);
    assertThat(inProgress.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_REVIEW.name());

    verify(llmExtractor, times(1)).extract(eq(documentId), eq(NEW_DOC_TYPE_ID));

    eventPublisher.publishEvent(
        new ExtractionCompleted(
            documentId, ORG_ID, newFields, NEW_DOC_TYPE_ID, Instant.parse("2026-04-29T11:00:00Z")));

    DocumentStateChanged completed = awaitEventWithReextractionStatus(ReextractionStatus.NONE);
    assertThat(completed.documentId()).isEqualTo(documentId);
    assertThat(completed.currentStage()).isEqualTo(STAGE_REVIEW_REC);
    assertThat(completed.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_REVIEW.name());
    assertThat(completed.action()).isNull();
    assertThat(completed.comment()).isNull();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Map<String, Object> docRow =
        jdbc.queryForMap(
            "SELECT detected_document_type, reextraction_status FROM documents WHERE id = ?",
            documentId);
    assertThat(docRow.get("detected_document_type")).isEqualTo(NEW_DOC_TYPE_ID);
    assertThat(docRow.get("reextraction_status")).isEqualTo("NONE");

    Map<String, Object> wiRow =
        jdbc.queryForMap(
            "SELECT current_stage_id, current_status, workflow_origin_stage, flag_comment,"
                + " document_type_id FROM workflow_instances WHERE document_id = ?",
            documentId);
    assertThat(wiRow.get("current_stage_id")).isEqualTo(STAGE_REVIEW_REC);
    assertThat(wiRow.get("current_status")).isEqualTo(WorkflowStatus.AWAITING_REVIEW.name());
    assertThat(wiRow.get("workflow_origin_stage")).isNull();
    assertThat(wiRow.get("flag_comment")).isNull();
    assertThat(wiRow.get("document_type_id")).isEqualTo(NEW_DOC_TYPE_ID);
  }

  @Test
  void resolveWithTypeChange_returnsImmediatelyAndListenerInvokesExtractorAsync()
      throws InterruptedException {
    UUID storedDocumentId = insertStoredDocument();
    UUID documentId =
        seedFlaggedDocument(storedDocumentId, ReextractionStatus.NONE, OLD_DOC_TYPE_ID);

    java.util.concurrent.CountDownLatch extractGate = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch extractEntered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Long> extractThreadId =
        new java.util.concurrent.atomic.AtomicReference<>();

    doAnswer(
            (InvocationOnMock inv) -> {
              extractThreadId.set(Thread.currentThread().threadId());
              extractEntered.countDown();
              extractGate.await(5, java.util.concurrent.TimeUnit.SECONDS);
              UUID docId = inv.getArgument(0);
              String newType = inv.getArgument(1);
              JdbcTemplate jdbc = new JdbcTemplate(dataSource);
              jdbc.update(
                  "UPDATE documents SET detected_document_type = ?,"
                      + " reextraction_status = 'NONE',"
                      + " extracted_fields = CAST(? AS jsonb) WHERE id = ?",
                  newType,
                  "{\"merchant\":\"Async Cafe\",\"total\":\"5.00\"}",
                  docId);
              eventPublisher.publishEvent(
                  new ExtractionCompleted(
                      docId,
                      ORG_ID,
                      Map.of("merchant", "Async Cafe", "total", "5.00"),
                      newType,
                      Instant.now()));
              return null;
            })
        .when(llmExtractor)
        .extract(eq(documentId), eq(NEW_DOC_TYPE_ID));

    long callerThreadId = Thread.currentThread().threadId();
    long startNs = System.nanoTime();
    workflowEngine.applyAction(documentId, new WorkflowAction.Resolve(NEW_DOC_TYPE_ID));
    long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

    assertThat(elapsedMs).isLessThan(2_000L);

    assertThat(extractEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(extractThreadId.get()).isNotNull().isNotEqualTo(callerThreadId);

    extractGate.countDown();

    DocumentStateChanged completed = awaitEventWithReextractionStatus(ReextractionStatus.NONE);
    assertThat(completed.documentId()).isEqualTo(documentId);
    assertThat(completed.currentStage()).isEqualTo(STAGE_REVIEW_REC);
    assertThat(completed.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_REVIEW.name());

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Map<String, Object> docRow =
        jdbc.queryForMap(
            "SELECT detected_document_type, reextraction_status FROM documents WHERE id = ?",
            documentId);
    assertThat(docRow.get("detected_document_type")).isEqualTo(NEW_DOC_TYPE_ID);
    assertThat(docRow.get("reextraction_status")).isEqualTo("NONE");
  }

  @Test
  void extractionFailed_publishesFailedStateEvent() {
    UUID storedDocumentId = insertStoredDocument();
    UUID documentId =
        seedFlaggedDocument(storedDocumentId, ReextractionStatus.FAILED, OLD_DOC_TYPE_ID);

    eventPublisher.publishEvent(
        new ExtractionFailed(
            documentId, ORG_ID, "schema violation", Instant.parse("2026-04-29T10:00:00Z")));

    await().atMost(Duration.ofSeconds(5)).until(() -> recorder.first() != null);

    DocumentStateChanged failed = recorder.first();
    assertThat(failed.documentId()).isEqualTo(documentId);
    assertThat(failed.storedDocumentId()).isEqualTo(storedDocumentId);
    assertThat(failed.organizationId()).isEqualTo(ORG_ID);
    assertThat(failed.reextractionStatus()).isEqualTo(ReextractionStatus.FAILED.name());
    assertThat(failed.currentStage()).isEqualTo(STAGE_REVIEW_INV);
    assertThat(failed.currentStatus()).isEqualTo(WorkflowStatus.FLAGGED.name());
    assertThat(failed.action()).isNull();
  }

  /**
   * Regression test for df-pv0: the production LlmExtractor.extract() writes reextraction_status =
   * NONE *before* publishing ExtractionCompleted. The old listener guard (== IN_PROGRESS) caused it
   * to silently drop the event, leaving the workflow_instance stuck in FLAGGED with stale origin
   * and comment. This test stubs extract() to reproduce the exact production ordering and verifies
   * that the workflow_instance is fully updated afterward.
   */
  @Test
  void extractionCompletedAfterReextractionNoneAlreadyWritten_workflowInstanceUpdated() {
    UUID storedDocumentId = insertStoredDocument();
    UUID documentId =
        seedFlaggedDocument(storedDocumentId, ReextractionStatus.NONE, OLD_DOC_TYPE_ID);

    Map<String, Object> newFields = new LinkedHashMap<>();
    newFields.put("merchant", "Corner Bakery");
    newFields.put("total", "9.99");

    doAnswer(
            (InvocationOnMock inv) -> {
              UUID docId = inv.getArgument(0);
              String newType = inv.getArgument(1);
              JdbcTemplate jdbc = new JdbcTemplate(dataSource);
              // Simulate production: write new fields + NONE before publishing event
              jdbc.update(
                  "UPDATE documents SET detected_document_type = ?, reextraction_status = 'NONE',"
                      + " extracted_fields = CAST(? AS jsonb) WHERE id = ?",
                  newType,
                  "{\"merchant\":\"Corner Bakery\",\"total\":\"9.99\"}",
                  docId);
              eventPublisher.publishEvent(
                  new ExtractionCompleted(docId, ORG_ID, newFields, newType, Instant.now()));
              return null;
            })
        .when(llmExtractor)
        .extract(eq(documentId), eq(NEW_DOC_TYPE_ID));

    workflowEngine.applyAction(documentId, new WorkflowAction.Resolve(NEW_DOC_TYPE_ID));

    DocumentStateChanged completed = awaitEventWithReextractionStatus(ReextractionStatus.NONE);
    assertThat(completed.documentId()).isEqualTo(documentId);
    assertThat(completed.currentStage()).isEqualTo(STAGE_REVIEW_REC);
    assertThat(completed.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_REVIEW.name());
    assertThat(completed.action()).isNull();
    assertThat(completed.comment()).isNull();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Map<String, Object> wiRow =
        jdbc.queryForMap(
            "SELECT current_stage_id, current_status, workflow_origin_stage, flag_comment,"
                + " document_type_id FROM workflow_instances WHERE document_id = ?",
            documentId);
    assertThat(wiRow.get("current_stage_id")).isEqualTo(STAGE_REVIEW_REC);
    assertThat(wiRow.get("current_status")).isEqualTo(WorkflowStatus.AWAITING_REVIEW.name());
    assertThat(wiRow.get("workflow_origin_stage")).isNull();
    assertThat(wiRow.get("flag_comment")).isNull();
    assertThat(wiRow.get("document_type_id")).isEqualTo(NEW_DOC_TYPE_ID);
  }

  @Test
  void extractionCompleted_writerThrows_listenerSwallowsAndLogsWarn() {
    UUID storedDocumentId = insertStoredDocument();
    UUID documentId =
        seedFlaggedDocument(storedDocumentId, ReextractionStatus.IN_PROGRESS, OLD_DOC_TYPE_ID);

    failingDocumentWriter.armUpdateExtractionFailure(new IllegalStateException("boom-update"));

    eventPublisher.publishEvent(
        new ExtractionCompleted(
            documentId,
            ORG_ID,
            Map.of("k", "v"),
            NEW_DOC_TYPE_ID,
            Instant.parse("2026-04-29T10:00:00Z")));

    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                listenerAppender.list.stream()
                    .anyMatch(
                        e ->
                            e.getLevel() == Level.WARN
                                && e.getFormattedMessage()
                                    .contains("ExtractionCompleted listener failed")));

    assertThat(recorder.events()).isEmpty();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Map<String, Object> docRow =
        jdbc.queryForMap(
            "SELECT detected_document_type, reextraction_status FROM documents WHERE id = ?",
            documentId);
    assertThat(docRow.get("detected_document_type")).isEqualTo(OLD_DOC_TYPE_ID);
    assertThat(docRow.get("reextraction_status")).isEqualTo("IN_PROGRESS");
  }

  @Test
  void extractionCompleted_unknownDocumentId_isLoggedAndDropped() {
    UUID unknownId = UuidCreator.getTimeOrderedEpoch();

    eventPublisher.publishEvent(
        new ExtractionCompleted(
            unknownId, ORG_ID, Map.of(), NEW_DOC_TYPE_ID, Instant.parse("2026-04-29T10:00:00Z")));

    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                listenerAppender.list.stream()
                    .anyMatch(
                        e ->
                            e.getLevel() == Level.WARN
                                && e.getFormattedMessage().contains("unknown documentId")));

    assertThat(recorder.events()).isEmpty();
  }

  private UUID insertStoredDocument() {
    UUID id = UuidCreator.getTimeOrderedEpoch();
    new JdbcTemplate(dataSource)
        .update(
            "INSERT INTO stored_documents "
                + "(id, organization_id, uploaded_at, source_filename, mime_type, storage_path) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            id,
            ORG_ID,
            Timestamp.from(Instant.now()),
            "src.pdf",
            "application/pdf",
            "/tmp/" + id + ".bin");
    return id;
  }

  private UUID seedUnflaggedReviewDocument(
      UUID storedDocumentId, ReextractionStatus status, String docTypeId) {
    UUID documentId = UuidCreator.getTimeOrderedEpoch();
    UUID workflowInstanceId = UuidCreator.getTimeOrderedEpoch();
    Instant now = Instant.now();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update(
        "INSERT INTO documents "
            + "(id, stored_document_id, organization_id, detected_document_type, "
            + "extracted_fields, raw_text, processed_at, reextraction_status) "
            + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)",
        documentId,
        storedDocumentId,
        ORG_ID,
        docTypeId,
        "{\"vendor\":\"Acme\"}",
        "raw text",
        Timestamp.from(now),
        status.name());
    jdbc.update(
        "INSERT INTO workflow_instances "
            + "(id, document_id, organization_id, document_type_id, current_stage_id, "
            + "current_status, workflow_origin_stage, flag_comment, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        workflowInstanceId,
        documentId,
        ORG_ID,
        docTypeId,
        STAGE_REVIEW_INV,
        WorkflowStatus.AWAITING_REVIEW.name(),
        null,
        null,
        Timestamp.from(now));
    return documentId;
  }

  private UUID seedFlaggedDocument(
      UUID storedDocumentId, ReextractionStatus status, String docTypeId) {
    UUID documentId = UuidCreator.getTimeOrderedEpoch();
    UUID workflowInstanceId = UuidCreator.getTimeOrderedEpoch();
    Instant now = Instant.now();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update(
        "INSERT INTO documents "
            + "(id, stored_document_id, organization_id, detected_document_type, "
            + "extracted_fields, raw_text, processed_at, reextraction_status) "
            + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)",
        documentId,
        storedDocumentId,
        ORG_ID,
        docTypeId,
        "{\"vendor\":\"Acme\"}",
        "raw text",
        Timestamp.from(now),
        status.name());
    jdbc.update(
        "INSERT INTO workflow_instances "
            + "(id, document_id, organization_id, document_type_id, current_stage_id, "
            + "current_status, workflow_origin_stage, flag_comment, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        workflowInstanceId,
        documentId,
        ORG_ID,
        docTypeId,
        STAGE_REVIEW_INV,
        WorkflowStatus.FLAGGED.name(),
        STAGE_MANAGER_INV,
        "needs receipt",
        Timestamp.from(now));
    return documentId;
  }

  @Component
  static class DocumentStateChangedRecorder {
    private final List<DocumentStateChanged> events = new CopyOnWriteArrayList<>();

    @EventListener
    void on(DocumentStateChanged event) {
      events.add(event);
    }

    List<DocumentStateChanged> events() {
      return List.copyOf(events);
    }

    DocumentStateChanged first() {
      return events.isEmpty() ? null : events.get(0);
    }

    void reset() {
      events.clear();
    }
  }

  static class FailingDocumentWriter implements DocumentWriter {
    private final DocumentWriter delegate;
    private volatile RuntimeException armedUpdateExtraction;

    FailingDocumentWriter(DocumentWriter delegate) {
      this.delegate = delegate;
    }

    void armUpdateExtractionFailure(RuntimeException e) {
      this.armedUpdateExtraction = e;
    }

    void reset() {
      this.armedUpdateExtraction = null;
    }

    @Override
    public void insert(Document document) {
      delegate.insert(document);
    }

    @Override
    public void updateExtraction(
        UUID documentId, String detectedDocumentType, Map<String, Object> fields) {
      RuntimeException toThrow = armedUpdateExtraction;
      if (toThrow != null) {
        armedUpdateExtraction = null;
        throw toThrow;
      }
      delegate.updateExtraction(documentId, detectedDocumentType, fields);
    }

    @Override
    public void setReextractionStatus(UUID documentId, ReextractionStatus status) {
      delegate.setReextractionStatus(documentId, status);
    }

    @Override
    public boolean claimReextractionInProgress(UUID documentId) {
      return delegate.claimReextractionInProgress(documentId);
    }
  }

  @SpringBootApplication
  @Import({
    AsyncConfig.class,
    DocumentEventBus.class,
    TimeConfig.class,
    OrgConfigSeedWriter.class,
    ConfigLoader.class,
    ConfigValidator.class,
    OrganizationCatalogImpl.class,
    DocumentTypeCatalogImpl.class,
    WorkflowCatalogImpl.class,
    JdbcDocumentReader.class,
    JdbcDocumentWriter.class,
    JdbcWorkflowInstanceReader.class,
    JdbcWorkflowInstanceWriter.class,
    ExtractionEventListener.class,
    RetypeRequestedListener.class
  })
  @EntityScan("com.docflow.config.persistence")
  @EnableJpaRepositories("com.docflow.config.persistence")
  @ConfigurationPropertiesScan("com.docflow.config")
  static class RetypeFlowIntegrationApp {

    @Bean(name = "orgConfigSeeder")
    OrgConfigSeeder orgConfigSeeder(
        AppConfig appConfig,
        ConfigLoader configLoader,
        ConfigValidator configValidator,
        OrgConfigSeedWriter writer,
        OrganizationRepository organizationRepository) {
      return new OrgConfigSeeder(
          appConfig, configLoader, configValidator, writer, organizationRepository);
    }

    @Bean
    DocumentStateChangedRecorder documentStateChangedRecorder() {
      return new DocumentStateChangedRecorder();
    }

    @Bean
    LlmExtractor llmExtractor() {
      return mock(LlmExtractor.class);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    FailingDocumentWriter failingDocumentWriter(JdbcDocumentWriter delegate) {
      return new FailingDocumentWriter(delegate);
    }

    @Bean
    WorkflowEngine workflowEngine(
        WorkflowCatalog catalog,
        DocumentReader documentReader,
        WorkflowInstanceReader instanceReader,
        WorkflowInstanceWriter instanceWriter,
        DocumentEventBus eventBus,
        Clock clock) {
      return new WorkflowEngine(
          catalog, documentReader, instanceReader, instanceWriter, eventBus, clock);
    }
  }
}
