package com.docflow.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.docflow.c3.events.ProcessingCompleted;
import com.docflow.c3.llm.LlmExtractor;
import com.docflow.config.AppConfig;
import com.docflow.config.catalog.DocumentTypeCatalogImpl;
import com.docflow.config.catalog.OrganizationCatalogImpl;
import com.docflow.config.catalog.WorkflowCatalogImpl;
import com.docflow.config.org.loader.ConfigLoader;
import com.docflow.config.org.seeder.OrgConfigSeedWriter;
import com.docflow.config.org.seeder.OrgConfigSeeder;
import com.docflow.config.org.validation.ConfigValidator;
import com.docflow.config.persistence.OrganizationRepository;
import com.docflow.document.internal.JdbcDocumentReader;
import com.docflow.document.internal.JdbcDocumentWriter;
import com.docflow.platform.AsyncConfig;
import com.docflow.platform.DocumentEventBus;
import com.docflow.platform.TimeConfig;
import com.docflow.workflow.WorkflowInstanceWriter;
import com.docflow.workflow.events.DocumentStateChanged;
import com.github.f4b6a3.uuid.UuidCreator;
import java.sql.Timestamp;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
    classes = ProcessingCompletedListenerIT.ListenerIntegrationApp.class,
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
class ProcessingCompletedListenerIT {

  private static final String ORG_ID = "test-org";
  private static final String DOC_TYPE_ID = "test-doc-type";
  private static final String REVIEW_STAGE_ID = "stage-rv";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withInitScripts(
              "db/migration/V1__init.sql", "fixtures/processing-completed-listener-seed.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private DataSource dataSource;
  @Autowired private DocumentStateChangedRecorder recorder;
  @Autowired private FailingWorkflowInstanceWriter failingWriter;

  private ListAppender<ILoggingEvent> listenerAppender;

  @BeforeEach
  void setUp() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM workflow_instances");
    jdbc.update("DELETE FROM documents");
    jdbc.update("DELETE FROM stored_documents");
    recorder.reset();
    failingWriter.reset();

    listenerAppender = new ListAppender<>();
    listenerAppender.start();
    Logger listenerLogger = (Logger) LoggerFactory.getLogger(ProcessingCompletedListener.class);
    listenerLogger.addAppender(listenerAppender);
    listenerLogger.setLevel(Level.WARN);
  }

  @AfterEach
  void tearDown() {
    Logger listenerLogger = (Logger) LoggerFactory.getLogger(ProcessingCompletedListener.class);
    listenerLogger.detachAppender(listenerAppender);
    listenerAppender.stop();
  }

  @Test
  void happyPath_persistsDocumentAndWorkflowInstanceAndPublishesStateChanged() {
    UUID storedDocumentId = insertStoredDocument();
    UUID processingDocumentId = UuidCreator.getTimeOrderedEpoch();
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("amount", "1.00");
    fields.put("vendor", "ACME");
    Instant occurredAt = Instant.parse("2026-04-29T10:00:00Z");

    ProcessingCompleted event =
        new ProcessingCompleted(
            storedDocumentId,
            processingDocumentId,
            ORG_ID,
            DOC_TYPE_ID,
            fields,
            "raw text body",
            occurredAt);

    eventPublisher.publishEvent(event);

    await().atMost(Duration.ofSeconds(5)).until(() -> recorder.first() != null);
    DocumentStateChanged published = recorder.first();

    assertThat(published.storedDocumentId()).isEqualTo(storedDocumentId);
    assertThat(published.organizationId()).isEqualTo(ORG_ID);
    assertThat(published.currentStage()).isEqualTo(REVIEW_STAGE_ID);
    assertThat(published.currentStatus()).isEqualTo("AWAITING_REVIEW");
    assertThat(published.reextractionStatus()).isEqualTo("NONE");
    assertThat(published.action()).isNull();
    assertThat(published.comment()).isNull();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Map<String, Object> docRow =
        jdbc.queryForMap(
            "SELECT id, stored_document_id, organization_id, detected_document_type, "
                + "raw_text, processed_at, reextraction_status "
                + "FROM documents WHERE stored_document_id = ?",
            storedDocumentId);
    assertThat(docRow.get("id")).isEqualTo(published.documentId());
    assertThat(docRow.get("stored_document_id")).isEqualTo(storedDocumentId);
    assertThat(docRow.get("organization_id")).isEqualTo(ORG_ID);
    assertThat(docRow.get("detected_document_type")).isEqualTo(DOC_TYPE_ID);
    assertThat(docRow.get("raw_text")).isEqualTo("raw text body");
    assertThat(docRow.get("reextraction_status")).isEqualTo("NONE");
    assertThat(((Timestamp) docRow.get("processed_at")).toInstant()).isNotNull();

    String fieldsJson =
        jdbc.queryForObject(
            "SELECT extracted_fields::text FROM documents WHERE id = ?",
            String.class,
            published.documentId());
    assertThat(fieldsJson)
        .contains("\"amount\"")
        .contains("\"1.00\"")
        .contains("\"vendor\"")
        .contains("\"ACME\"");

    Map<String, Object> wiRow =
        jdbc.queryForMap(
            "SELECT document_id, organization_id, document_type_id, current_stage_id, "
                + "current_status, workflow_origin_stage, flag_comment "
                + "FROM workflow_instances WHERE document_id = ?",
            published.documentId());
    assertThat(wiRow.get("document_id")).isEqualTo(published.documentId());
    assertThat(wiRow.get("organization_id")).isEqualTo(ORG_ID);
    assertThat(wiRow.get("document_type_id")).isEqualTo(DOC_TYPE_ID);
    assertThat(wiRow.get("current_stage_id")).isEqualTo(REVIEW_STAGE_ID);
    assertThat(wiRow.get("current_status")).isEqualTo("AWAITING_REVIEW");
    assertThat(wiRow.get("workflow_origin_stage")).isNull();
    assertThat(wiRow.get("flag_comment")).isNull();

    assertThat(recorder.events()).hasSize(1);
  }

  @Test
  void publishedEventValuesComeFromEventPayload() {
    UUID storedDocumentId = insertStoredDocument();
    Map<String, Object> fields = Map.of("k", "v");
    Instant occurredAt = Instant.parse("2026-04-29T10:00:00Z");

    ProcessingCompleted event =
        new ProcessingCompleted(
            storedDocumentId,
            UuidCreator.getTimeOrderedEpoch(),
            ORG_ID,
            DOC_TYPE_ID,
            fields,
            "rt",
            occurredAt);

    eventPublisher.publishEvent(event);

    await().atMost(Duration.ofSeconds(5)).until(() -> recorder.first() != null);
    DocumentStateChanged published = recorder.first();

    assertThat(published.storedDocumentId()).isEqualTo(event.storedDocumentId());
    assertThat(published.organizationId()).isEqualTo(event.organizationId());
    assertThat(published.currentStage()).isEqualTo(REVIEW_STAGE_ID);
    assertThat(published.currentStatus()).isEqualTo("AWAITING_REVIEW");
    assertThat(published.reextractionStatus()).isEqualTo("NONE");

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Map<String, Object> docRow =
        jdbc.queryForMap(
            "SELECT detected_document_type, raw_text FROM documents WHERE stored_document_id = ?",
            storedDocumentId);
    assertThat(docRow.get("detected_document_type")).isEqualTo(event.detectedDocumentType());
    assertThat(docRow.get("raw_text")).isEqualTo(event.rawText());
  }

  @Test
  void midTransactionFailure_rollsBackBothRowsAndDoesNotPublish() {
    UUID storedDocumentId = insertStoredDocument();
    failingWriter.armRuntimeException("boom-mid-txn");

    ProcessingCompleted event =
        new ProcessingCompleted(
            storedDocumentId,
            UuidCreator.getTimeOrderedEpoch(),
            ORG_ID,
            DOC_TYPE_ID,
            Map.of("k", "v"),
            "rt",
            Instant.parse("2026-04-29T10:00:00Z"));

    eventPublisher.publishEvent(event);

    await().atMost(Duration.ofSeconds(5)).until(failingWriter::wasInvoked);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Long docCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM documents WHERE stored_document_id = ?",
            Long.class,
            storedDocumentId);
    assertThat(docCount).isZero();
    Long wiCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM workflow_instances WHERE organization_id = ?",
            Long.class,
            ORG_ID);
    assertThat(wiCount).isZero();
    assertThat(recorder.events()).isEmpty();
  }

  @Test
  void duplicateStoredDocumentId_rollsBackAndLogsWarnAndDoesNotPublishSecondEvent() {
    UUID storedDocumentId = insertStoredDocument();
    Instant occurredAt = Instant.parse("2026-04-29T10:00:00Z");

    ProcessingCompleted firstEvent =
        new ProcessingCompleted(
            storedDocumentId,
            UuidCreator.getTimeOrderedEpoch(),
            ORG_ID,
            DOC_TYPE_ID,
            Map.of("k", "v"),
            "rt",
            occurredAt);

    eventPublisher.publishEvent(firstEvent);
    await().atMost(Duration.ofSeconds(5)).until(() -> recorder.events().size() == 1);

    ProcessingCompleted duplicateEvent =
        new ProcessingCompleted(
            storedDocumentId,
            UuidCreator.getTimeOrderedEpoch(),
            ORG_ID,
            DOC_TYPE_ID,
            Map.of("k2", "v2"),
            "rt2",
            occurredAt.plusSeconds(1));

    eventPublisher.publishEvent(duplicateEvent);

    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                listenerAppender.list.stream()
                    .anyMatch(
                        e ->
                            e.getLevel() == Level.WARN
                                && e.getFormattedMessage().contains("duplicate storedDocumentId")));

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Long docCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM documents WHERE stored_document_id = ?",
            Long.class,
            storedDocumentId);
    assertThat(docCount).isEqualTo(1L);

    assertThat(recorder.events())
        .as("only the first event yields a DocumentStateChanged")
        .hasSize(1);
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

  /**
   * Wraps the production WorkflowInstanceWriter so we can simulate a mid-transaction failure for a
   * single invocation. Marked {@link Primary} so the listener resolves to this delegating bean.
   */
  static class FailingWorkflowInstanceWriter implements WorkflowInstanceWriter {
    private final WorkflowInstanceWriter delegate;
    private volatile RuntimeException armed;
    private volatile boolean invoked;

    FailingWorkflowInstanceWriter(WorkflowInstanceWriter delegate) {
      this.delegate = delegate;
    }

    void armRuntimeException(String message) {
      this.armed = new IllegalStateException(message);
    }

    boolean wasInvoked() {
      return invoked;
    }

    void reset() {
      this.armed = null;
      this.invoked = false;
    }

    @Override
    public void insert(com.docflow.workflow.WorkflowInstance instance, String documentTypeId) {
      invoked = true;
      RuntimeException toThrow = armed;
      if (toThrow != null) {
        armed = null;
        throw toThrow;
      }
      delegate.insert(instance, documentTypeId);
    }

    @Override
    public void advanceStage(
        UUID documentId,
        String newStageId,
        com.docflow.config.catalog.WorkflowCatalog catalog,
        String orgId,
        String docTypeId) {
      delegate.advanceStage(documentId, newStageId, catalog, orgId, docTypeId);
    }

    @Override
    public void setFlag(
        UUID documentId,
        String originStageId,
        String comment,
        com.docflow.config.catalog.WorkflowCatalog catalog,
        String orgId,
        String docTypeId) {
      delegate.setFlag(documentId, originStageId, comment, catalog, orgId, docTypeId);
    }

    @Override
    public void clearFlag(
        UUID documentId,
        com.docflow.config.catalog.WorkflowCatalog catalog,
        String orgId,
        String docTypeId) {
      delegate.clearFlag(documentId, catalog, orgId, docTypeId);
    }

    @Override
    public void clearOriginKeepStage(
        UUID documentId,
        com.docflow.config.catalog.WorkflowCatalog catalog,
        String orgId,
        String newDocTypeId) {
      delegate.clearOriginKeepStage(documentId, catalog, orgId, newDocTypeId);
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
    JdbcWorkflowInstanceWriter.class,
    ProcessingCompletedListener.class
  })
  @EntityScan("com.docflow.config.persistence")
  @EnableJpaRepositories("com.docflow.config.persistence")
  @ConfigurationPropertiesScan("com.docflow.config")
  static class ListenerIntegrationApp {

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
    @Primary
    FailingWorkflowInstanceWriter failingWorkflowInstanceWriter(
        JdbcWorkflowInstanceWriter delegate) {
      return new FailingWorkflowInstanceWriter(delegate);
    }

    @Bean
    LlmExtractor llmExtractor() {
      return mock(LlmExtractor.class);
    }
  }
}
