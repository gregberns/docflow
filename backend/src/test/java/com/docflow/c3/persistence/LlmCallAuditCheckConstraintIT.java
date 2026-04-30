package com.docflow.c3.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fragment-level schema test for c3-processing.sql. Loads minimal FK stubs for stored_documents
 * (C2) and documents (C4), then the C3 fragment, then verifies (a) the tables exist with the
 * expected columns / FKs / indexes / CHECKs, and (b) the mutual-exclusivity CHECK on llm_call_audit
 * rejects rows with neither and rows with both of (processing_document_id, document_id).
 *
 * <p>Per the brief, cross-fragment FK targets are exercised once C7.4 (df-9c2.4) assembles
 * V1__init.sql; this IT scopes the CHECK exercise to processing_document_id (which FKs to a
 * fragment-local table) since the constraint is column-presence-based, not FK-based.
 */
@Testcontainers
class LlmCallAuditCheckConstraintIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @BeforeAll
  static void loadFragments() throws SQLException {
    try (Connection conn =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      runScript(conn, "db/fragment-stubs/c3-fk-stubs.sql");
      runScript(conn, "db/migration/fragments/c3-processing.sql");
    }
  }

  private static void runScript(Connection conn, String resourcePath) throws SQLException {
    String sql = readResource(resourcePath);
    try (Statement st = conn.createStatement()) {
      st.execute(sql);
    }
  }

  private static String readResource(String resourcePath) {
    try {
      return new String(
          new ClassPathResource(resourcePath).getInputStream().readAllBytes(),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void processingDocumentsTableExistsWithExpectedColumns() throws SQLException {
    assertThat(columnNames("processing_documents"))
        .containsExactlyInAnyOrder(
            "id",
            "stored_document_id",
            "organization_id",
            "current_step",
            "raw_text",
            "last_error",
            "created_at",
            "updated_at");
    assertThat(checkConstraintNames("processing_documents"))
        .contains("ck_processing_documents_current_step");
    assertThat(indexNames("processing_documents"))
        .contains("idx_processing_documents_org_created", "idx_processing_documents_stored");
  }

  @Test
  void llmCallAuditTableExistsWithExpectedColumnsAndConstraints() throws SQLException {
    assertThat(columnNames("llm_call_audit"))
        .containsExactlyInAnyOrder(
            "id",
            "stored_document_id",
            "processing_document_id",
            "document_id",
            "organization_id",
            "call_type",
            "model_id",
            "error",
            "at");
    assertThat(checkConstraintNames("llm_call_audit"))
        .contains("ck_llm_call_audit_call_type", "ck_llm_call_audit_subject_exclusive");
    assertThat(indexNames("llm_call_audit"))
        .contains(
            "idx_llm_call_audit_stored_at", "idx_llm_call_audit_proc", "idx_llm_call_audit_doc");
  }

  @Test
  void checkRejectsRowWithNeitherSubject() throws SQLException {
    UUID storedDocId = insertStoredDocument();

    assertThatThrownBy(
            () -> insertAuditRow(storedDocId, /* procDocId= */ null, /* documentId= */ null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_llm_call_audit_subject_exclusive");
  }

  @Test
  void checkRejectsRowWithBothSubjects() throws SQLException {
    UUID storedDocId = insertStoredDocument();
    UUID procDocId = insertProcessingDocument(storedDocId);
    UUID documentId = insertDocumentStub();

    assertThatThrownBy(() -> insertAuditRow(storedDocId, procDocId, documentId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_llm_call_audit_subject_exclusive");
  }

  @Test
  void checkAcceptsRowWithOnlyProcessingDocumentId() throws SQLException {
    UUID storedDocId = insertStoredDocument();
    UUID procDocId = insertProcessingDocument(storedDocId);

    UUID auditId = insertAuditRow(storedDocId, procDocId, null);

    assertThat(auditId).isNotNull();
  }

  @Test
  void checkAcceptsRowWithOnlyDocumentId() throws SQLException {
    UUID storedDocId = insertStoredDocument();
    UUID documentId = insertDocumentStub();

    UUID auditId = insertAuditRow(storedDocId, null, documentId);

    assertThat(auditId).isNotNull();
  }

  private static UUID insertStoredDocument() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement("INSERT INTO stored_documents (id) VALUES (?)")) {
      ps.setObject(1, id);
      ps.executeUpdate();
    }
    return id;
  }

  private static UUID insertDocumentStub() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection conn = openConnection();
        PreparedStatement ps = conn.prepareStatement("INSERT INTO documents (id) VALUES (?)")) {
      ps.setObject(1, id);
      ps.executeUpdate();
    }
    return id;
  }

  private static UUID insertProcessingDocument(UUID storedDocId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO processing_documents "
                    + "(id, stored_document_id, organization_id, current_step) "
                    + "VALUES (?, ?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, storedDocId);
      ps.setString(3, "riverside-bistro");
      ps.setString(4, "TEXT_EXTRACTING");
      ps.executeUpdate();
    }
    return id;
  }

  private static UUID insertAuditRow(UUID storedDocId, UUID procDocId, UUID documentId)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO llm_call_audit "
                    + "(id, stored_document_id, processing_document_id, document_id, "
                    + " organization_id, call_type, model_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, storedDocId);
      ps.setObject(3, procDocId);
      ps.setObject(4, documentId);
      ps.setString(5, "riverside-bistro");
      ps.setString(6, "classify");
      ps.setString(7, "claude-sonnet-4-6");
      ps.executeUpdate();
    }
    return id;
  }

  private static Connection openConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static java.util.List<String> columnNames(String table) throws SQLException {
    java.util.List<String> result = new java.util.ArrayList<>();
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = ?")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    }
    return result;
  }

  private static java.util.List<String> checkConstraintNames(String table) throws SQLException {
    java.util.List<String> result = new java.util.ArrayList<>();
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT conname FROM pg_constraint "
                    + "WHERE contype = 'c' AND conrelid = ?::regclass")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    }
    return result;
  }

  private static java.util.List<String> indexNames(String table) throws SQLException {
    java.util.List<String> result = new java.util.ArrayList<>();
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT indexname FROM pg_indexes "
                    + "WHERE schemaname = 'public' AND tablename = ?")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    }
    return result;
  }
}
