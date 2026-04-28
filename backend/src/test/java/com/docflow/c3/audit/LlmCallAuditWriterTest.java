package com.docflow.c3.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.ingestion.StoredDocumentId;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
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

@Testcontainers
@SpringBootTest(
    classes = LlmCallAuditWriterTest.JpaTestApp.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none"
    })
class LlmCallAuditWriterTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withInitScripts(
              "db/migration/fragments/c1-org-config.sql",
              "db/migration/fragments/c2-stored-documents.sql",
              "db/migration/fragments/c4-workflow.sql",
              "db/migration/fragments/c3-processing.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  private static final String ORG_ID = "riverside-construction";
  private static final String DOC_TYPE_ID = "riverside_invoice";
  private static final String MODEL_ID = "claude-sonnet-4-6";

  @Autowired private LlmCallAuditWriter writer;
  @Autowired private LlmCallAuditReader reader;
  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbc;
  private StoredDocumentId storedDocumentId;
  private UUID processingDocumentId;
  private UUID documentId;

  @BeforeEach
  void seed() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM llm_call_audit");
    jdbc.update("DELETE FROM workflow_instances");
    jdbc.update("DELETE FROM documents");
    jdbc.update("DELETE FROM processing_documents");
    jdbc.update("DELETE FROM stored_documents");
    jdbc.update("DELETE FROM document_types");
    jdbc.update("DELETE FROM organizations");

    jdbc.update(
        "INSERT INTO organizations (id, display_name, icon_id) VALUES (?, ?, ?)",
        ORG_ID,
        "Riverside Construction",
        "icon-riverside");
    jdbc.update(
        "INSERT INTO document_types (organization_id, id, display_name, field_schema)"
            + " VALUES (?, ?, ?, '{}'::jsonb)",
        ORG_ID,
        DOC_TYPE_ID,
        "Invoice");

    storedDocumentId = StoredDocumentId.generate();
    jdbc.update(
        "INSERT INTO stored_documents"
            + " (id, organization_id, uploaded_at, source_filename, mime_type, storage_path)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        storedDocumentId.value(),
        ORG_ID,
        Timestamp.from(Instant.now()),
        "invoice.pdf",
        "application/pdf",
        "./storage/" + storedDocumentId.value());

    processingDocumentId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO processing_documents"
            + " (id, stored_document_id, organization_id, current_step) VALUES (?, ?, ?, ?)",
        processingDocumentId,
        storedDocumentId.value(),
        ORG_ID,
        "CLASSIFYING");

    documentId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO documents"
            + " (id, stored_document_id, organization_id, detected_document_type,"
            + " extracted_fields, processed_at)"
            + " VALUES (?, ?, ?, ?, '{}'::jsonb, ?)",
        documentId,
        storedDocumentId.value(),
        ORG_ID,
        DOC_TYPE_ID,
        Timestamp.from(Instant.now()));
  }

  @Test
  void initialPipelineShapePersists() {
    Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
    LlmCallAudit audit =
        new LlmCallAudit(
            LlmCallAuditId.generate(),
            storedDocumentId,
            processingDocumentId,
            null,
            ORG_ID,
            CallType.CLASSIFY,
            MODEL_ID,
            null,
            at);

    writer.insert(audit);

    List<LlmCallAudit> rows = reader.listForStoredDocument(storedDocumentId);
    assertThat(rows).hasSize(1);
    LlmCallAudit row = rows.get(0);
    assertThat(row.processingDocumentId()).isEqualTo(processingDocumentId);
    assertThat(row.documentId()).isNull();
    assertThat(row.callType()).isEqualTo(CallType.CLASSIFY);
    assertThat(row.organizationId()).isEqualTo(ORG_ID);
    assertThat(row.modelId()).isEqualTo(MODEL_ID);
    assertThat(row.error()).isNull();
    assertThat(row.at()).isEqualTo(at);

    String dbCallType =
        jdbc.queryForObject(
            "SELECT call_type FROM llm_call_audit WHERE id = ?", String.class, audit.id().value());
    assertThat(dbCallType).isEqualTo("classify");
  }

  @Test
  void retypeShapePersists() {
    Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
    LlmCallAudit audit =
        new LlmCallAudit(
            LlmCallAuditId.generate(),
            storedDocumentId,
            null,
            documentId,
            ORG_ID,
            CallType.EXTRACT,
            MODEL_ID,
            null,
            at);

    writer.insert(audit);

    List<LlmCallAudit> rows = reader.listForStoredDocument(storedDocumentId);
    assertThat(rows).hasSize(1);
    LlmCallAudit row = rows.get(0);
    assertThat(row.processingDocumentId()).isNull();
    assertThat(row.documentId()).isEqualTo(documentId);
    assertThat(row.callType()).isEqualTo(CallType.EXTRACT);

    String dbCallType =
        jdbc.queryForObject(
            "SELECT call_type FROM llm_call_audit WHERE id = ?", String.class, audit.id().value());
    assertThat(dbCallType).isEqualTo("extract");
  }

  @Test
  void rejectsBothFkColumnsSetWithoutInsert() {
    LlmCallAudit audit =
        new LlmCallAudit(
            LlmCallAuditId.generate(),
            storedDocumentId,
            processingDocumentId,
            documentId,
            ORG_ID,
            CallType.CLASSIFY,
            MODEL_ID,
            null,
            Instant.now());

    assertThatThrownBy(() -> writer.insert(audit)).isInstanceOf(IllegalArgumentException.class);

    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM llm_call_audit", Integer.class);
    assertThat(count).isZero();
  }

  @Test
  void rejectsNeitherFkColumnSetWithoutInsert() {
    LlmCallAudit audit =
        new LlmCallAudit(
            LlmCallAuditId.generate(),
            storedDocumentId,
            null,
            null,
            ORG_ID,
            CallType.CLASSIFY,
            MODEL_ID,
            null,
            Instant.now());

    assertThatThrownBy(() -> writer.insert(audit)).isInstanceOf(IllegalArgumentException.class);

    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM llm_call_audit", Integer.class);
    assertThat(count).isZero();
  }

  @Test
  void writesOneRowPerCallTypePerStoredDocument() {
    Instant classifyAt = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
    Instant extractAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    writer.insert(
        new LlmCallAudit(
            LlmCallAuditId.generate(),
            storedDocumentId,
            processingDocumentId,
            null,
            ORG_ID,
            CallType.CLASSIFY,
            MODEL_ID,
            null,
            classifyAt));
    writer.insert(
        new LlmCallAudit(
            LlmCallAuditId.generate(),
            storedDocumentId,
            processingDocumentId,
            null,
            ORG_ID,
            CallType.EXTRACT,
            MODEL_ID,
            null,
            extractAt));

    List<LlmCallAudit> rows = reader.listForStoredDocument(storedDocumentId);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).callType()).isEqualTo(CallType.EXTRACT);
    assertThat(rows.get(1).callType()).isEqualTo(CallType.CLASSIFY);
  }

  @SpringBootApplication
  static class JpaTestApp {}
}
