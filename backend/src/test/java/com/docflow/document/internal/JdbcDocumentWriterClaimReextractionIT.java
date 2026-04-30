package com.docflow.document.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.f4b6a3.uuid.UuidCreator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    classes = JdbcDocumentWriterClaimReextractionIT.JdbcWriterTestApp.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none",
      "docflow.llm.model-id=ignored",
      "docflow.llm.api-key=ignored",
      "docflow.llm.request-timeout=PT60S",
      "docflow.llm.eval.report-path=eval/reports/latest.md",
      "docflow.storage.storage-root=/tmp/docflow",
      "docflow.database.url=ignored",
      "docflow.database.user=ignored",
      "docflow.database.password=ignored",
      "docflow.config.seed-on-boot=false",
      "docflow.config.seed-resource-path=classpath:seed/"
    })
class JdbcDocumentWriterClaimReextractionIT {

  private static final String ORG_ID = "test-org";
  private static final String DOC_TYPE_ID = "invoice";

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

  @Autowired private JdbcDocumentWriter writer;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void setUp() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM workflow_instances");
    jdbc.update("DELETE FROM documents");
    jdbc.update("DELETE FROM stored_documents");
  }

  @Test
  void claimReextractionInProgress_concurrentCallers_onlyOneWins() throws Exception {
    UUID documentId = seedDocumentWithStatus("NONE");

    int threadCount = 2;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    AtomicInteger losers = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    try {
      Future<?> a =
          pool.submit(
              () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                if (writer.claimReextractionInProgress(documentId)) {
                  winners.incrementAndGet();
                } else {
                  losers.incrementAndGet();
                }
                return null;
              });
      Future<?> b =
          pool.submit(
              () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                if (writer.claimReextractionInProgress(documentId)) {
                  winners.incrementAndGet();
                } else {
                  losers.incrementAndGet();
                }
                return null;
              });

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(winners.get()).isEqualTo(1);
    assertThat(losers.get()).isEqualTo(1);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String status =
        jdbc.queryForObject(
            "SELECT reextraction_status FROM documents WHERE id = ?", String.class, documentId);
    assertThat(status).isEqualTo("IN_PROGRESS");
  }

  @Test
  void claimReextractionInProgress_alreadyInProgress_returnsFalse() {
    UUID documentId = seedDocumentWithStatus("IN_PROGRESS");

    boolean claimed = writer.claimReextractionInProgress(documentId);

    assertThat(claimed).isFalse();
  }

  @Test
  void claimReextractionInProgress_freshDocument_returnsTrue() {
    UUID documentId = seedDocumentWithStatus("NONE");

    boolean claimed = writer.claimReextractionInProgress(documentId);

    assertThat(claimed).isTrue();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String status =
        jdbc.queryForObject(
            "SELECT reextraction_status FROM documents WHERE id = ?", String.class, documentId);
    assertThat(status).isEqualTo("IN_PROGRESS");
  }

  @Test
  void claimReextractionInProgress_failedDocument_returnsTrue() {
    UUID documentId = seedDocumentWithStatus("FAILED");

    boolean claimed = writer.claimReextractionInProgress(documentId);

    assertThat(claimed).isTrue();
  }

  private UUID seedDocumentWithStatus(String status) {
    UUID storedDocumentId = UuidCreator.getTimeOrderedEpoch();
    UUID documentId = UuidCreator.getTimeOrderedEpoch();
    Instant now = Instant.now();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update(
        "INSERT INTO stored_documents "
            + "(id, organization_id, uploaded_at, source_filename, mime_type, storage_path) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        storedDocumentId,
        ORG_ID,
        Timestamp.from(now),
        "src.pdf",
        "application/pdf",
        "/tmp/" + storedDocumentId + ".bin");
    jdbc.update(
        "INSERT INTO documents "
            + "(id, stored_document_id, organization_id, detected_document_type, "
            + "extracted_fields, raw_text, processed_at, reextraction_status) "
            + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)",
        documentId,
        storedDocumentId,
        ORG_ID,
        DOC_TYPE_ID,
        "{}",
        "raw",
        Timestamp.from(now),
        status);
    return documentId;
  }

  @SpringBootApplication
  @Import(JdbcDocumentWriter.class)
  static class JdbcWriterTestApp {}
}
