package com.docflow.api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.f4b6a3.uuid.UuidCreator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
class ProcessingDocumentCleanupJobIT {

  private static final String ORG_ID = "cleanup-org";
  private static final Instant FIXED_NOW = Instant.parse("2026-04-29T12:00:00Z");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("db/migration/V1__init.sql"),
              "/docker-entrypoint-initdb.d/01-init.sql")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource(
                  "db/migration/V3__add_updated_at_to_processing_documents.sql"),
              "/docker-entrypoint-initdb.d/03-add-updated-at.sql");

  private DataSource dataSource;
  private JdbcTemplate jdbc;
  private ProcessingDocumentCleanupJob job;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    ds.setDriverClassName("org.postgresql.Driver");
    dataSource = ds;

    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM processing_documents");
    jdbc.update("DELETE FROM stored_documents");
    jdbc.update("DELETE FROM organizations");
    jdbc.update(
        "INSERT INTO organizations (id, display_name, icon_id, ordinal) VALUES (?, ?, ?, ?)",
        ORG_ID,
        "Cleanup Org",
        "icon",
        0);

    job =
        new ProcessingDocumentCleanupJob(
            new NamedParameterJdbcTemplate(dataSource), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
  }

  @Test
  void sweep_deletesFailedRowsOlderThanRetention() {
    UUID storedOld = insertStored("old.pdf");
    UUID failedOldId = insertProcessing(storedOld, "FAILED", FIXED_NOW.minusSeconds(86400 * 8));
    UUID storedRecent = insertStored("recent.pdf");
    UUID failedRecentId = insertProcessing(storedRecent, "FAILED", FIXED_NOW.minusSeconds(86400));

    int deleted = job.sweep();

    assertThat(deleted).isEqualTo(1);
    List<UUID> remaining = jdbc.queryForList("SELECT id FROM processing_documents", UUID.class);
    assertThat(remaining).contains(failedRecentId).doesNotContain(failedOldId);
  }

  @Test
  void sweep_doesNotTouchInFlightRows() {
    UUID stored = insertStored("in-flight.pdf");
    UUID inFlightId = insertProcessing(stored, "CLASSIFYING", FIXED_NOW.minusSeconds(86400 * 30));

    int deleted = job.sweep();

    assertThat(deleted).isZero();
    List<UUID> remaining = jdbc.queryForList("SELECT id FROM processing_documents", UUID.class);
    assertThat(remaining).containsExactly(inFlightId);
  }

  private UUID insertStored(String filename) {
    UUID id = UuidCreator.getTimeOrderedEpoch();
    jdbc.update(
        "INSERT INTO stored_documents "
            + "(id, organization_id, uploaded_at, source_filename, mime_type, storage_path) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        id,
        ORG_ID,
        Timestamp.from(FIXED_NOW.minusSeconds(86400 * 30)),
        filename,
        "application/pdf",
        "/tmp/" + id);
    return id;
  }

  private UUID insertProcessing(UUID storedId, String currentStep, Instant updatedAt) {
    UUID id = UuidCreator.getTimeOrderedEpoch();
    Timestamp ts = Timestamp.from(updatedAt);
    jdbc.update(
        "INSERT INTO processing_documents "
            + "(id, stored_document_id, organization_id, current_step, raw_text, last_error, "
            + "created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, NULL, NULL, ?, ?)",
        id,
        storedId,
        ORG_ID,
        currentStep,
        ts,
        ts);
    return id;
  }
}
