package com.docflow.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import com.docflow.ingestion.StoredDocumentWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test (df-qzh): proves that {@link StoredDocumentWriter#insert(StoredDocument)} is
 * visible to a same-transaction {@link StoredDocumentReader#get} call. With the prior raw-JDBC
 * INSERT this would bypass Hibernate's persistence context; switching to the JPA writer means the
 * read finds the row via the same session.
 */
@Testcontainers
@SpringBootTest(
    classes = StoredDocumentSameTransactionIT.JpaTestApp.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none"
    })
class StoredDocumentSameTransactionIT {

  private static final String ORG_ID = "riverside-bistro";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withInitScripts(
              "db/migration/fragments/c1-org-config.sql",
              "db/migration/fragments/c2-stored-documents.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private StoredDocumentWriter writer;
  @Autowired private StoredDocumentReader reader;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void seedOrg() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update(
        "INSERT INTO organizations (id, display_name, icon_id, ordinal) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING",
        ORG_ID,
        "Riverside Bistro",
        "icon-riverside-bistro",
        0);
  }

  @Test
  void insertVisibleToReaderInSameTransaction() {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    StoredDocumentId id = StoredDocumentId.generate();
    Instant uploadedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    String storagePath = "./storage/" + id.value() + ".bin";

    StoredDocument loaded =
        tx.execute(
            status -> {
              writer.insert(
                  new StoredDocument(
                      id, ORG_ID, uploadedAt, "report.pdf", "application/pdf", storagePath));
              return reader.get(id).orElseThrow();
            });

    assertThat(loaded).isNotNull();
    assertThat(loaded.id()).isEqualTo(id);
    assertThat(loaded.organizationId()).isEqualTo(ORG_ID);
    assertThat(loaded.uploadedAt()).isEqualTo(uploadedAt);
    assertThat(loaded.sourceFilename()).isEqualTo("report.pdf");
    assertThat(loaded.mimeType()).isEqualTo("application/pdf");
    assertThat(loaded.storagePath()).isEqualTo(storagePath);
  }

  @SpringBootApplication(scanBasePackages = "com.docflow.ingestion.internal")
  static class JpaTestApp {}
}
