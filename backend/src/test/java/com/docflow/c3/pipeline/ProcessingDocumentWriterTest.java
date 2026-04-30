package com.docflow.c3.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.ingestion.StoredDocumentId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    classes = ProcessingDocumentWriterTest.JpaTestApp.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none"
    })
class ProcessingDocumentWriterTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withInitScripts(
              "db/migration/fragments/c1-org-config.sql",
              "db/migration/fragments/c2-stored-documents.sql",
              "db/fragment-stubs/c3-fk-stubs-documents.sql",
              "db/migration/fragments/c3-processing.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private ProcessingDocumentWriter writer;
  @Autowired private ProcessingDocumentReader reader;
  @Autowired private DataSource dataSource;

  @Test
  void insertPersistsRowVisibleToReader() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertOrganization(jdbc, "riverside-bistro");

    StoredDocumentId storedId = StoredDocumentId.generate();
    Instant uploadedAt = Instant.now();
    jdbc.update(
        "INSERT INTO stored_documents "
            + "(id, organization_id, uploaded_at, source_filename, mime_type, storage_path) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        storedId.value(),
        "riverside-bistro",
        Timestamp.from(uploadedAt),
        "doc.pdf",
        "application/pdf",
        "./storage/" + storedId.value() + ".bin");

    ProcessingDocumentId procId = ProcessingDocumentId.generate();
    Instant createdAt = Instant.now();
    writer.insert(procId, storedId, "riverside-bistro", "TEXT_EXTRACTING", createdAt);

    ProcessingDocument loaded = reader.get(procId).orElseThrow();
    assertThat(loaded.id()).isEqualTo(procId);
    assertThat(loaded.storedDocumentId()).isEqualTo(storedId);
    assertThat(loaded.organizationId()).isEqualTo("riverside-bistro");
    assertThat(loaded.currentStep()).isEqualTo("TEXT_EXTRACTING");
    assertThat(loaded.rawText()).isNull();
    assertThat(loaded.lastError()).isNull();
  }

  @Test
  void updateStepPersists() {
    Fixture f = seed("riverside-bistro");

    writer.updateStep(f.processingDocumentId(), "CLASSIFYING");

    ProcessingDocument loaded = reader.get(f.processingDocumentId()).orElseThrow();
    assertThat(loaded.currentStep()).isEqualTo("CLASSIFYING");
    assertThat(loaded.organizationId()).isEqualTo("riverside-bistro");
    assertThat(loaded.storedDocumentId()).isEqualTo(f.storedDocumentId());
  }

  @Test
  void updateRawTextPersists() {
    Fixture f = seed("riverside-bistro");

    writer.updateRawText(f.processingDocumentId(), "extracted text body");

    ProcessingDocument loaded = reader.get(f.processingDocumentId()).orElseThrow();
    assertThat(loaded.rawText()).isEqualTo("extracted text body");
    assertThat(loaded.currentStep()).isEqualTo("TEXT_EXTRACTING");
  }

  @Test
  void markFailedSetsStepAndError() {
    Fixture f = seed("riverside-bistro");

    writer.markFailed(f.processingDocumentId(), "pdfbox parse error");

    ProcessingDocument loaded = reader.get(f.processingDocumentId()).orElseThrow();
    assertThat(loaded.currentStep()).isEqualTo("FAILED");
    assertThat(loaded.lastError()).isEqualTo("pdfbox parse error");
  }

  @Test
  void organizationMismatchThrows() {
    Fixture f = seed("riverside-bistro");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertOrganization(jdbc, "pinnacle-legal");
    jdbc.update(
        "UPDATE processing_documents SET organization_id = ? WHERE id = ?",
        "pinnacle-legal",
        f.processingDocumentId().value());

    assertThatThrownBy(() -> writer.updateStep(f.processingDocumentId(), "CLASSIFYING"))
        .isInstanceOf(ProcessingDocumentOrganizationMismatchException.class)
        .hasMessageContaining("pinnacle-legal")
        .hasMessageContaining("riverside-bistro");

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT current_step FROM processing_documents WHERE id = ?",
            f.processingDocumentId().value());
    assertThat(row).containsEntry("current_step", "TEXT_EXTRACTING");
  }

  @Test
  void notFoundThrows() {
    ProcessingDocumentId missing = ProcessingDocumentId.generate();

    assertThatThrownBy(() -> writer.updateStep(missing, "CLASSIFYING"))
        .isInstanceOf(ProcessingDocumentNotFoundException.class);
  }

  private Fixture seed(String organizationId) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertOrganization(jdbc, organizationId);

    StoredDocumentId storedId = StoredDocumentId.generate();
    Instant uploadedAt = Instant.now();
    jdbc.update(
        "INSERT INTO stored_documents "
            + "(id, organization_id, uploaded_at, source_filename, mime_type, storage_path) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        storedId.value(),
        organizationId,
        Timestamp.from(uploadedAt),
        "doc.pdf",
        "application/pdf",
        "./storage/" + storedId.value() + ".bin");

    ProcessingDocumentId procId = ProcessingDocumentId.generate();
    jdbc.update(
        "INSERT INTO processing_documents "
            + "(id, stored_document_id, organization_id, current_step) "
            + "VALUES (?, ?, ?, ?)",
        procId.value(),
        storedId.value(),
        organizationId,
        "TEXT_EXTRACTING");

    return new Fixture(procId, storedId);
  }

  private static void insertOrganization(JdbcTemplate jdbc, String organizationId) {
    jdbc.update(
        "INSERT INTO organizations (id, display_name, icon_id, ordinal) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING",
        organizationId,
        organizationId,
        "icon-" + organizationId,
        0);
  }

  private record Fixture(
      ProcessingDocumentId processingDocumentId, StoredDocumentId storedDocumentId) {}

  @SpringBootApplication(
      scanBasePackages = {"com.docflow.c3.pipeline.internal", "com.docflow.ingestion.internal"})
  @EntityScan(basePackages = "com.docflow.ingestion.internal")
  @EnableJpaRepositories(basePackages = "com.docflow.ingestion.internal")
  static class JpaTestApp {}
}
