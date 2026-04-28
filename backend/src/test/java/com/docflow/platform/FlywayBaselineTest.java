package com.docflow.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the Flyway V1 baseline assembled by C7.4. Drives Flyway directly against a
 * Testcontainer Postgres without a full Spring context. Covers:
 *
 * <ul>
 *   <li>AC4-a: empty DB migrates cleanly and produces every expected table.
 *   <li>AC4-b: a second migrate against an already-migrated DB is a no-op (one history row).
 *   <li>AC4-c: tampering with V1__init.sql after apply fails on checksum validation.
 *   <li>Schema-level: workflow_instances.current_status enumerates the 5 canonical values;
 *       documents.reextraction_status enumerates NONE/IN_PROGRESS/FAILED; llm_call_audit's
 *       mutual-exclusivity CHECK is present.
 *   <li>Migration directory contains exactly one V*.sql file (V1__init.sql).
 * </ul>
 */
@Testcontainers
class FlywayBaselineTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @BeforeEach
  void resetDatabase() throws SQLException {
    try (Connection conn = openConnection();
        java.sql.Statement st = conn.createStatement()) {
      st.execute("DROP SCHEMA public CASCADE");
      st.execute("CREATE SCHEMA public");
    }
  }

  @Test
  void emptyDatabaseMigratesCleanly() throws SQLException {
    Flyway flyway = classpathFlyway();

    flyway.migrate();

    List<String> tables = userTables();
    assertThat(tables)
        .containsExactlyInAnyOrder(
            "organizations",
            "document_types",
            "organization_doc_types",
            "workflows",
            "stages",
            "transitions",
            "stored_documents",
            "processing_documents",
            "documents",
            "workflow_instances",
            "llm_call_audit");
  }

  @Test
  void reMigrateIsNoOpAndHistoryHasSingleEntry() throws SQLException {
    Flyway flyway = classpathFlyway();

    flyway.migrate();
    flyway.migrate();

    int historyRows = countSchemaHistoryRowsForVersion("1");
    assertThat(historyRows)
        .as("flyway_schema_history must have exactly one applied entry for V1")
        .isEqualTo(1);
  }

  @Test
  void editingV1AfterApplyFailsChecksum(@TempDir Path tmp) throws Exception {
    Flyway initial = classpathFlyway();
    initial.migrate();

    Path tamperedDir = tmp.resolve("migration");
    Files.createDirectories(tamperedDir);
    String original = readClasspathV1();
    String tampered = original + "\n-- tampered after apply\n";
    Files.writeString(tamperedDir.resolve("V1__init.sql"), tampered, StandardCharsets.UTF_8);

    Flyway tamperedFlyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("filesystem:" + tamperedDir.toAbsolutePath())
            .baselineOnMigrate(false)
            .load();

    assertThatThrownBy(tamperedFlyway::migrate)
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void workflowInstancesCurrentStatusCheckEnumeratesFiveCanonicalValues() throws SQLException {
    classpathFlyway().migrate();

    String clause = checkClause("workflow_instances", "ck_workflow_instances_current_status");
    assertThat(clause)
        .contains("AWAITING_REVIEW")
        .contains("FLAGGED")
        .contains("AWAITING_APPROVAL")
        .contains("FILED")
        .contains("REJECTED");
  }

  @Test
  void documentsReextractionStatusCheckEnumeratesNoneInProgressFailed() throws SQLException {
    classpathFlyway().migrate();

    String clause = checkClause("documents", "ck_documents_reextraction_status");
    assertThat(clause).contains("NONE").contains("IN_PROGRESS").contains("FAILED");
  }

  @Test
  void llmCallAuditMutualExclusivityCheckIsPresent() throws SQLException {
    classpathFlyway().migrate();

    String clause = checkClause("llm_call_audit", "ck_llm_call_audit_subject_exclusive");
    assertThat(clause).contains("processing_document_id").contains("document_id");
  }

  @Test
  void migrationDirectoryContainsExactlyOneVersionedScript() throws Exception {
    URL dir = Thread.currentThread().getContextClassLoader().getResource("db/migration");
    assertThat(dir).as("classpath:db/migration must be present").isNotNull();

    Path migrationDir = Paths.get(dir.toURI());
    List<String> versioned;
    try (Stream<Path> stream = Files.list(migrationDir)) {
      versioned =
          stream
              .filter(Files::isRegularFile)
              .map(p -> p.getFileName().toString())
              .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
              .sorted()
              .toList();
    }
    assertThat(versioned).containsExactly("V1__init.sql");
  }

  private static Flyway classpathFlyway() {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .load();
  }

  private static Connection openConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static List<String> userTables() throws SQLException {
    List<String> result = new ArrayList<>();
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                    + "AND table_name <> 'flyway_schema_history'")) {
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    }
    return result;
  }

  private static int countSchemaHistoryRowsForVersion(String version) throws SQLException {
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version = ? AND success = TRUE")) {
      ps.setString(1, version);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static String checkClause(String tableName, String constraintName) throws SQLException {
    try (Connection conn = openConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT pg_get_constraintdef(con.oid) AS def "
                    + "  FROM pg_constraint con "
                    + "  JOIN pg_class cls ON cls.oid = con.conrelid "
                    + "  WHERE cls.relname = ? AND con.conname = ?")) {
      ps.setString(1, tableName);
      ps.setString(2, constraintName);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next())
            .as("constraint %s on %s must exist", constraintName, tableName)
            .isTrue();
        return rs.getString("def");
      }
    }
  }

  private static String readClasspathV1() throws IOException {
    URL url =
        Thread.currentThread().getContextClassLoader().getResource("db/migration/V1__init.sql");
    if (url == null) {
      throw new IllegalStateException("classpath:db/migration/V1__init.sql is missing");
    }
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
