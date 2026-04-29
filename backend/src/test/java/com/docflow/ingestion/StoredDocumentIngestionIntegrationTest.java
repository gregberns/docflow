package com.docflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.c3.events.StoredDocumentIngested;
import com.docflow.config.AppConfig;
import com.docflow.ingestion.storage.StoredDocumentStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AC-R4 / AC-R7 / AC-EVENT end-to-end. Boots a minimal {@link IngestionIntegrationApp} (ingestion +
 * config + platform packages only — the rest of the production graph still has unimplemented
 * read-side seams) against a Postgres testcontainer with the full {@code V1__init.sql} applied via
 * the container init script and a tmpfs storage root, then drives one upload through {@link
 * StoredDocumentIngestionService} and verifies all six stored-document columns, the on-disk bytes,
 * the initial processing-document row, and exactly one post-commit StoredDocumentIngested event.
 */
@Testcontainers
@RecordApplicationEvents
@SpringBootTest(
    classes = StoredDocumentIngestionIntegrationTest.IngestionIntegrationApp.class,
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
class StoredDocumentIngestionIntegrationTest {

  private static final String ORG_ID = "pinnacle-legal";
  private static @TempDir Path STORAGE_ROOT;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withInitScripts("db/migration/V1__init.sql", "fixtures/pinnacle-legal-seed.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("docflow.storage.storage-root", () -> STORAGE_ROOT.toString());
  }

  @Autowired private StoredDocumentIngestionService service;
  @Autowired private DataSource dataSource;
  @Autowired private StoredDocumentStorage storage;
  @Autowired private ApplicationEvents applicationEvents;
  @Autowired private IngestedRecorder ingestedRecorder;

  @Test
  void uploadRoundTrip_persistsRowFilesAndPostCommitEvent() throws IOException {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    ingestedRecorder.reset();

    byte[] bytes = readFixture("fixtures/sample-invoice.pdf");
    Instant beforeUpload = Instant.now();

    IngestionResult result = service.upload(ORG_ID, "invoice.pdf", "application/pdf", bytes);

    assertThat(result.storedDocumentId()).isNotNull();
    assertThat(result.processingDocumentId()).isNotNull();
    assertThat(result.storedDocumentId()).isNotEqualTo(result.processingDocumentId());

    var storedRow =
        jdbc.queryForMap(
            "SELECT id, organization_id, uploaded_at, source_filename, mime_type, storage_path "
                + "FROM stored_documents WHERE id = ?",
            result.storedDocumentId());
    assertThat(storedRow)
        .containsOnlyKeys(
            "id", "organization_id", "uploaded_at", "source_filename", "mime_type", "storage_path");
    assertThat(storedRow.get("id")).isEqualTo(result.storedDocumentId());
    assertThat(storedRow.get("organization_id")).isEqualTo(ORG_ID);
    assertThat(storedRow.get("source_filename")).isEqualTo("invoice.pdf");
    assertThat(storedRow.get("mime_type")).isEqualTo("application/pdf");
    Instant uploadedAt = ((Timestamp) storedRow.get("uploaded_at")).toInstant();
    assertThat(uploadedAt).isAfterOrEqualTo(beforeUpload.minusSeconds(1));
    String expectedStoragePath =
        STORAGE_ROOT.resolve(result.storedDocumentId() + ".bin").toString();
    assertThat(storedRow.get("storage_path")).isEqualTo(expectedStoragePath);

    Path onDisk = STORAGE_ROOT.resolve(result.storedDocumentId() + ".bin");
    assertThat(onDisk).exists();
    assertThat(Files.readAllBytes(onDisk)).containsExactly(bytes);
    byte[] viaStorage = storage.load(StoredDocumentId.of(result.storedDocumentId()));
    assertThat(viaStorage).containsExactly(bytes);

    var procRow =
        jdbc.queryForMap(
            "SELECT id, stored_document_id, organization_id, current_step "
                + "FROM processing_documents WHERE id = ?",
            result.processingDocumentId());
    assertThat(procRow.get("id")).isEqualTo(result.processingDocumentId());
    assertThat(procRow.get("stored_document_id")).isEqualTo(result.storedDocumentId());
    assertThat(procRow.get("organization_id")).isEqualTo(ORG_ID);
    assertThat(procRow.get("current_step")).isEqualTo("TEXT_EXTRACTING");

    List<StoredDocumentIngested> recorded =
        applicationEvents.stream(StoredDocumentIngested.class).toList();
    assertThat(recorded).hasSize(1);
    StoredDocumentIngested event = recorded.get(0);
    assertThat(event.storedDocumentId()).isEqualTo(result.storedDocumentId());
    assertThat(event.processingDocumentId()).isEqualTo(result.processingDocumentId());
    assertThat(event.organizationId()).isEqualTo(ORG_ID);

    List<IngestedRecorder.Observation> observations = ingestedRecorder.observedEvents();
    assertThat(observations).hasSize(1);
    IngestedRecorder.Observation obs = observations.get(0);
    assertThat(obs.event().storedDocumentId()).isEqualTo(result.storedDocumentId());
    assertThat(obs.dbRowVisible())
        .as("listener fired after commit; the stored_documents row must be visible by then")
        .isTrue();
  }

  private static byte[] readFixture(String classpathResource) throws IOException {
    try (InputStream in =
        StoredDocumentIngestionIntegrationTest.class
            .getClassLoader()
            .getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IOException("Missing fixture: " + classpathResource);
      }
      return in.readAllBytes();
    }
  }

  /**
   * Captures every {@link StoredDocumentIngested} the bus delivers, plus a snapshot of whether the
   * stored_documents row is visible at delivery time. The publisher uses afterCommit
   * synchronization so the listener fires once the tx has committed; the snapshot is the
   * load-bearing assertion that publish-after-commit ordering is honored.
   */
  static class IngestedRecorder {
    private final DataSource dataSource;
    private final List<Observation> events = new java.util.ArrayList<>();

    IngestedRecorder(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    @EventListener
    void onIngested(StoredDocumentIngested event) {
      Long count =
          new JdbcTemplate(dataSource)
              .queryForObject(
                  "SELECT count(*) FROM stored_documents WHERE id = ?",
                  Long.class,
                  event.storedDocumentId());
      events.add(new Observation(event, count != null && count == 1L));
    }

    List<Observation> observedEvents() {
      return List.copyOf(events);
    }

    void reset() {
      events.clear();
    }

    record Observation(StoredDocumentIngested event, boolean dbRowVisible) {}
  }

  @SpringBootApplication(
      scanBasePackages = {"com.docflow.ingestion", "com.docflow.config", "com.docflow.platform"})
  @EntityScan({"com.docflow.ingestion.internal", "com.docflow.config.persistence"})
  @EnableJpaRepositories({"com.docflow.ingestion.internal", "com.docflow.config.persistence"})
  @ConfigurationPropertiesScan("com.docflow.config")
  static class IngestionIntegrationApp {

    @Bean
    AppConfig.Storage appStorage(AppConfig appConfig) {
      return appConfig.storage();
    }

    @Bean
    IngestedRecorder ingestedRecorder(DataSource dataSource) {
      return new IngestedRecorder(dataSource);
    }
  }
}
