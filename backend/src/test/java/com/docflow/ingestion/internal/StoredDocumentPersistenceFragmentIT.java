package com.docflow.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fragment-level mapping test: loads c1-org-config.sql + c2-stored-documents.sql into a fresh
 * Postgres so the JPA entity + reader can round-trip rows ahead of C7.4's V1__init.sql assembly.
 * When df-9c2.4 lands, this test should be folded into the full V1-driven integration suite.
 */
@Testcontainers
@SpringBootTest(
    classes = StoredDocumentPersistenceFragmentIT.JpaTestApp.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none"
    })
class StoredDocumentPersistenceFragmentIT {

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

  @Autowired private StoredDocumentEntityRepository repository;
  @Autowired private StoredDocumentReader reader;
  @Autowired private DataSource dataSource;

  @Test
  void schemaHasColumnsAndOrgUploadedIndex() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Map<String, Object> idCol = singleRow(jdbc, columnInfoSql("id"));
    assertThat(idCol).containsEntry("data_type", "uuid").containsEntry("is_nullable", "NO");

    Map<String, Object> orgCol = singleRow(jdbc, columnInfoSql("organization_id"));
    assertThat(orgCol)
        .containsEntry("data_type", "character varying")
        .containsEntry("is_nullable", "NO");

    Map<String, Object> uploadedCol = singleRow(jdbc, columnInfoSql("uploaded_at"));
    assertThat(uploadedCol)
        .containsEntry("data_type", "timestamp with time zone")
        .containsEntry("is_nullable", "NO");

    assertThat(jdbc.queryForList(columnInfoSql("source_filename"))).hasSize(1);
    assertThat(jdbc.queryForList(columnInfoSql("mime_type"))).hasSize(1);
    assertThat(jdbc.queryForList(columnInfoSql("storage_path"))).hasSize(1);

    String indexDef =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes "
                + "WHERE schemaname = 'public' AND tablename = 'stored_documents' "
                + "AND indexname = 'idx_stored_documents_org_uploaded'",
            String.class);
    assertThat(indexDef).isNotNull().contains("organization_id").contains("uploaded_at DESC");
  }

  @Test
  void readerReturnsRowRegardlessOfOrg() {
    String orgId = "pinnacle-legal";
    new JdbcTemplate(dataSource)
        .update(
            "INSERT INTO organizations (id, display_name, icon_id, ordinal) VALUES (?, ?, ?, ?)",
            orgId,
            "Pinnacle Legal",
            "icon-pinnacle-legal",
            0);

    StoredDocumentId id = StoredDocumentId.generate();
    Instant uploadedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    repository.save(
        new StoredDocumentEntity(
            id.value(),
            orgId,
            uploadedAt,
            "report.pdf",
            "application/pdf",
            "./storage/documents/" + id.value() + ".bin"));

    StoredDocument loaded = reader.get(id).orElseThrow();
    assertThat(loaded.id()).isEqualTo(id);
    assertThat(loaded.organizationId()).isEqualTo(orgId);
    assertThat(loaded.uploadedAt()).isEqualTo(uploadedAt);
    assertThat(loaded.sourceFilename()).isEqualTo("report.pdf");
    assertThat(loaded.mimeType()).isEqualTo("application/pdf");
    assertThat(loaded.storagePath()).contains(id.value().toString());
  }

  private static String columnInfoSql(String column) {
    return "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
        + "WHERE table_schema = 'public' AND table_name = 'stored_documents' "
        + "AND column_name = '"
        + column
        + "'";
  }

  private static Map<String, Object> singleRow(JdbcTemplate jdbc, String sql) {
    return jdbc.queryForList(sql).stream()
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected exactly one row from: " + sql));
  }

  @SpringBootApplication
  static class JpaTestApp {}
}
