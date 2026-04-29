package com.docflow.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.api.error.UnknownOrganizationException;
import com.docflow.c3.events.StoredDocumentIngested;
import com.docflow.config.AppConfig;
import com.docflow.ingestion.IngestionResult;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentIngestionService;
import com.docflow.ingestion.UnsupportedMediaTypeException;
import com.docflow.ingestion.storage.FilesystemStoredDocumentStorage;
import com.docflow.ingestion.storage.StoredDocumentStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    classes = StoredDocumentIngestionServiceImplTest.IngestionTestApp.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none",
      "docflow.llm.model-id=claude-sonnet-4-6",
      "docflow.llm.api-key=sk-ant-test",
      "docflow.llm.request-timeout=PT60S",
      "docflow.llm.eval.report-path=eval/reports/latest.md",
      "docflow.database.url=ignored",
      "docflow.database.user=ignored",
      "docflow.database.password=ignored",
      "docflow.config.seed-on-boot=false",
      "docflow.config.seed-resource-path=classpath:seed/"
    })
class StoredDocumentIngestionServiceImplTest {

  private static final String ORG_ID = "test-org";
  private static @TempDir Path STORAGE_ROOT;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withInitScripts(
              "db/migration/fragments/c1-org-config.sql",
              "db/migration/fragments/c2-stored-documents.sql",
              "db/migration/fragments/c4-workflow.sql",
              "db/migration/fragments/c3-processing.sql",
              "fixtures/test-org-seed.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("docflow.storage.storage-root", () -> STORAGE_ROOT.toString());
  }

  @Autowired private StoredDocumentIngestionService service;
  @Autowired private DataSource dataSource;
  @Autowired private RecordingEventListener eventListener;
  @Autowired private ObservingStorageWrapper storageObserver;

  private JdbcTemplate jdbc;

  @BeforeEach
  void setup() throws IOException {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM processing_documents");
    jdbc.update("DELETE FROM stored_documents");
    eventListener.events.clear();
    if (Files.exists(STORAGE_ROOT)) {
      try (var stream = Files.list(STORAGE_ROOT)) {
        for (Path p : stream.toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }

  @Test
  void acR1_pdfBodyAcceptedReturnsBothUuids() throws IOException {
    byte[] bytes = readFixture("fixtures/sample-invoice.pdf");

    IngestionResult result = service.upload(ORG_ID, "invoice.pdf", "application/pdf", bytes);

    assertThat(result.storedDocumentId()).isNotNull();
    assertThat(result.processingDocumentId()).isNotNull();
    assertThat(result.storedDocumentId()).isNotEqualTo(result.processingDocumentId());
  }

  @Test
  void acR1_textBytesRejectedAs415() throws IOException {
    byte[] bytes = readFixture("fixtures/not-a-pdf.txt");

    assertThatThrownBy(() -> service.upload(ORG_ID, "note.txt", "text/csv", bytes))
        .isInstanceOf(UnsupportedMediaTypeException.class);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_documents", Long.class))
        .isEqualTo(0L);
  }

  @Test
  void acR1_pdfBytesWithTextPlainContentTypeAcceptedTikaWins() throws IOException {
    byte[] bytes = readFixture("fixtures/sample-invoice.pdf");

    IngestionResult result = service.upload(ORG_ID, "invoice.pdf", "text/plain", bytes);

    assertThat(result.storedDocumentId()).isNotNull();
    String storedMime =
        jdbc.queryForObject(
            "SELECT mime_type FROM stored_documents WHERE id = ?",
            String.class,
            result.storedDocumentId());
    assertThat(storedMime).isEqualTo("application/pdf");
  }

  @Test
  void acR4_successPathLeavesBothRowsAndPublishesEventAfterCommit() throws IOException {
    byte[] bytes = readFixture("fixtures/sample-invoice.pdf");

    IngestionResult result = service.upload(ORG_ID, "invoice.pdf", "application/pdf", bytes);

    Long storedRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM stored_documents WHERE id = ?",
            Long.class,
            result.storedDocumentId());
    assertThat(storedRows).isEqualTo(1L);

    String currentStep =
        jdbc.queryForObject(
            "SELECT current_step FROM processing_documents WHERE id = ?",
            String.class,
            result.processingDocumentId());
    assertThat(currentStep).isEqualTo("TEXT_EXTRACTING");

    UUID storedRef =
        jdbc.queryForObject(
            "SELECT stored_document_id FROM processing_documents WHERE id = ?",
            UUID.class,
            result.processingDocumentId());
    assertThat(storedRef).isEqualTo(result.storedDocumentId());

    assertThat(eventListener.events).hasSize(1);
    StoredDocumentIngested event = eventListener.events.get(0);
    assertThat(event.storedDocumentId()).isEqualTo(result.storedDocumentId());
    assertThat(event.processingDocumentId()).isEqualTo(result.processingDocumentId());
    assertThat(event.organizationId()).isEqualTo(ORG_ID);
  }

  @Test
  void acR4_faultBetweenInsertsRollsBothBackAndDoesNotPublishEvent() throws IOException {
    byte[] bytes = readFixture("fixtures/sample-invoice.pdf");
    eventListener.events.clear();

    String unknownOrg = "no-such-org";
    assertThatThrownBy(() -> service.upload(unknownOrg, "invoice.pdf", "application/pdf", bytes))
        .isInstanceOf(UnknownOrganizationException.class);

    assertThat(eventListener.events).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_documents", Long.class))
        .isEqualTo(0L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM processing_documents", Long.class))
        .isEqualTo(0L);
  }

  @Test
  void acR4_processingInsertFailureRollsBackBothRows() throws IOException {
    byte[] bytes = readFixture("fixtures/sample-invoice.pdf");
    jdbc.update(
        "ALTER TABLE processing_documents DROP CONSTRAINT ck_processing_documents_current_step");
    jdbc.update(
        "ALTER TABLE processing_documents ADD CONSTRAINT ck_processing_documents_current_step "
            + "CHECK (current_step = 'IMPOSSIBLE_FORCE_FAIL')");

    try {
      assertThatThrownBy(() -> service.upload(ORG_ID, "invoice.pdf", "application/pdf", bytes))
          .isInstanceOf(RuntimeException.class);

      assertThat(eventListener.events).isEmpty();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_documents", Long.class))
          .isEqualTo(0L);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM processing_documents", Long.class))
          .isEqualTo(0L);
    } finally {
      jdbc.update(
          "ALTER TABLE processing_documents DROP CONSTRAINT ck_processing_documents_current_step");
      jdbc.update(
          "ALTER TABLE processing_documents ADD CONSTRAINT ck_processing_documents_current_step "
              + "CHECK (current_step IN ('TEXT_EXTRACTING', 'CLASSIFYING', 'EXTRACTING', 'FAILED'))");
    }
  }

  @Test
  void acR9a_fileWrittenBeforeDbRowCommits() throws IOException {
    byte[] bytes = readFixture("fixtures/sample-invoice.pdf");
    storageObserver.reset();

    IngestionResult result = service.upload(ORG_ID, "invoice.pdf", "application/pdf", bytes);

    assertThat(storageObserver.fileExistedAtSaveTime).isTrue();
    assertThat(storageObserver.dbRowCountAtSaveTime).isEqualTo(0L);

    Path expectedFile = STORAGE_ROOT.resolve(result.storedDocumentId() + ".bin");
    assertThat(expectedFile).exists();
    Long postCommitRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM stored_documents WHERE id = ?",
            Long.class,
            result.storedDocumentId());
    assertThat(postCommitRows).isEqualTo(1L);
  }

  @Test
  void acOrgValidation_unknownOrgThrowsAndDoesNotCreateFsOrDb() throws IOException {
    byte[] bytes = readFixture("fixtures/sample-invoice.pdf");
    long fsBefore = countFiles(STORAGE_ROOT);

    assertThatThrownBy(() -> service.upload("ghost-org", "invoice.pdf", "application/pdf", bytes))
        .isInstanceOf(UnknownOrganizationException.class);

    assertThat(countFiles(STORAGE_ROOT)).isEqualTo(fsBefore);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_documents", Long.class))
        .isEqualTo(0L);
  }

  @Test
  void acCorruptPdf_zeroBytesRejected() throws IOException {
    byte[] bytes = readFixture("fixtures/zero-bytes.bin");
    long fsBefore = countFiles(STORAGE_ROOT);

    assertThatThrownBy(() -> service.upload(ORG_ID, "empty.pdf", "application/pdf", bytes))
        .isInstanceOf(UnsupportedMediaTypeException.class);

    assertThat(countFiles(STORAGE_ROOT)).isEqualTo(fsBefore);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_documents", Long.class))
        .isEqualTo(0L);
  }

  private static byte[] readFixture(String classpathResource) throws IOException {
    try (InputStream in =
        StoredDocumentIngestionServiceImplTest.class
            .getClassLoader()
            .getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IOException("Missing fixture: " + classpathResource);
      }
      return in.readAllBytes();
    }
  }

  private static long countFiles(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return 0;
    }
    try (var stream = Files.list(dir)) {
      return stream.count();
    }
  }

  static class RecordingEventListener {
    final List<StoredDocumentIngested> events = new ArrayList<>();

    @EventListener
    void onIngested(StoredDocumentIngested event) {
      events.add(event);
    }
  }

  static final class ObservingStorageWrapper implements StoredDocumentStorage {
    private final FilesystemStoredDocumentStorage delegate;
    private final DataSource dataSource;
    boolean fileExistedAtSaveTime;
    long dbRowCountAtSaveTime;

    ObservingStorageWrapper(FilesystemStoredDocumentStorage delegate, DataSource dataSource) {
      this.delegate = delegate;
      this.dataSource = dataSource;
    }

    void reset() {
      fileExistedAtSaveTime = false;
      dbRowCountAtSaveTime = -1L;
    }

    @Override
    public void save(StoredDocumentId id, byte[] bytes) {
      delegate.save(id, bytes);
      Path resolved = STORAGE_ROOT.resolve(id.value() + ".bin");
      fileExistedAtSaveTime = Files.exists(resolved);
      Long count =
          new JdbcTemplate(dataSource)
              .queryForObject("SELECT count(*) FROM stored_documents", Long.class);
      dbRowCountAtSaveTime = count == null ? 0L : count;
    }

    @Override
    public byte[] load(StoredDocumentId id) {
      return delegate.load(id);
    }

    @Override
    public void delete(StoredDocumentId id) {
      delegate.delete(id);
    }
  }

  @SpringBootApplication(
      scanBasePackages = {"com.docflow.ingestion", "com.docflow.config", "com.docflow.platform"})
  @EntityScan({"com.docflow.ingestion.internal", "com.docflow.config.persistence"})
  @EnableJpaRepositories({"com.docflow.ingestion.internal", "com.docflow.config.persistence"})
  @ConfigurationPropertiesScan("com.docflow.config")
  static class IngestionTestApp {

    @Bean
    AppConfig.Storage appStorage(AppConfig appConfig) {
      return appConfig.storage();
    }

    @Bean
    @Primary
    ObservingStorageWrapper observingStorage(AppConfig appConfig, DataSource dataSource) {
      return new ObservingStorageWrapper(
          new FilesystemStoredDocumentStorage(appConfig.storage()), dataSource);
    }

    @Bean
    RecordingEventListener recordingEventListener() {
      return new RecordingEventListener();
    }
  }
}
