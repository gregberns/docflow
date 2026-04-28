package com.docflow.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fragment-level integration test for c4-workflow.sql. Boots a fresh Postgres via Testcontainers
 * and applies, in dependency order, the C1 client-data fragment, a minimal stored_documents stub
 * standing in for the C2 fragment (df-9c2.4 will replace this with the sibling fragment when C7.4
 * assembles V1__init.sql), and the C4 fragment under test. Asserts the indexes and CHECK
 * constraints required by C4-R11 / C4-R13 via information_schema and pg_indexes.
 */
@Testcontainers
class SchemaIndexExistenceTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @BeforeAll
  static void applyFragments() throws SQLException, IOException {
    try (Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      executeSql(connection, readClasspath("db/migration/fragments/c1-org-config.sql"));
      executeSql(connection, storedDocumentsStub());
      executeSql(connection, readClasspath("db/migration/fragments/c4-workflow.sql"));
    }
  }

  /**
   * Minimal stored_documents stand-in for fragment-level testing. Carries only the columns
   * referenced by c4-workflow.sql's FK so the migration replays cleanly. Replaced by C2's actual
   * fragment when C7.4 (df-9c2.4) assembles V1__init.sql.
   */
  private static String storedDocumentsStub() {
    return """
        CREATE TABLE stored_documents (
            id              UUID         PRIMARY KEY,
            organization_id VARCHAR(255) NOT NULL REFERENCES organizations (id)
        );
        """;
  }

  private static String readClasspath(String resource) throws IOException {
    try (InputStream in =
        SchemaIndexExistenceTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("Missing classpath resource: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void executeSql(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  @Test
  void documentsHasUniqueStoredDocumentIdIndex() throws SQLException {
    assertThat(uniqueIndexedColumns("documents"))
        .as("documents must enforce UNIQUE(stored_document_id)")
        .anyMatch(cols -> cols.equals(List.of("stored_document_id")));
  }

  @Test
  void documentsHasOrgProcessedAtDescIndex() throws SQLException {
    assertThat(indexDefinitions("documents"))
        .as("documents must index (organization_id, processed_at DESC)")
        .anyMatch(
            def ->
                def.contains("organization_id")
                    && def.contains("processed_at")
                    && def.toUpperCase(java.util.Locale.ROOT).contains("DESC"));
  }

  @Test
  void documentsReextractionStatusCheckEnumeratesNoneInProgressFailed() throws SQLException {
    String clause = checkClause("documents", "ck_documents_reextraction_status");
    assertThat(clause).contains("NONE").contains("IN_PROGRESS").contains("FAILED");
  }

  @Test
  void documentsReextractionStatusDefaultsToNone() throws SQLException {
    String defaultExpr = columnDefault("documents", "reextraction_status");
    assertThat(defaultExpr).isNotNull().contains("NONE");
  }

  @Test
  void workflowInstancesHasUniqueDocumentIdIndex() throws SQLException {
    assertThat(uniqueIndexedColumns("workflow_instances"))
        .as("workflow_instances must enforce UNIQUE(document_id)")
        .anyMatch(cols -> cols.equals(List.of("document_id")));
  }

  @Test
  void workflowInstancesHasOrgStatusUpdatedAtIndex() throws SQLException {
    assertThat(indexDefinitions("workflow_instances"))
        .as("workflow_instances must index (organization_id, current_status, updated_at DESC)")
        .anyMatch(
            def ->
                def.contains("organization_id")
                    && def.contains("current_status")
                    && def.contains("updated_at")
                    && def.toUpperCase(java.util.Locale.ROOT).contains("DESC"));
  }

  @Test
  void workflowInstancesCurrentStatusCheckEnumeratesFiveCanonicalValues() throws SQLException {
    String clause = checkClause("workflow_instances", "ck_workflow_instances_current_status");
    assertThat(clause)
        .contains("AWAITING_REVIEW")
        .contains("FLAGGED")
        .contains("AWAITING_APPROVAL")
        .contains("FILED")
        .contains("REJECTED");
  }

  private static List<List<String>> uniqueIndexedColumns(String tableName) throws SQLException {
    String sql =
        """
        SELECT ic.relname AS index_name, a.attname
          FROM pg_index i
          JOIN pg_class c ON c.oid = i.indrelid
          JOIN pg_class ic ON ic.oid = i.indexrelid
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY (i.indkey)
          WHERE c.relname = ?
            AND i.indisunique
          ORDER BY ic.relname, array_position(i.indkey, a.attnum)
        """;
    List<String> indexNames = new ArrayList<>();
    List<String> columns = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, tableName);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          indexNames.add(rs.getString("index_name"));
          columns.add(rs.getString("attname"));
        }
      }
    }
    return groupColumnsByIndex(indexNames, columns);
  }

  private static List<List<String>> groupColumnsByIndex(
      List<String> indexNames, List<String> columns) {
    Map<String, List<String>> byIndex =
        java.util.stream.IntStream.range(0, indexNames.size())
            .boxed()
            .collect(
                Collectors.groupingBy(
                    indexNames::get,
                    LinkedHashMap::new,
                    Collectors.mapping(columns::get, Collectors.toList())));
    return List.copyOf(byIndex.values());
  }

  private static List<String> indexDefinitions(String tableName) throws SQLException {
    List<String> defs = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            connection.prepareStatement("SELECT indexdef FROM pg_indexes WHERE tablename = ?")) {
      ps.setString(1, tableName);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          defs.add(rs.getString("indexdef"));
        }
      }
    }
    return defs;
  }

  private static String checkClause(String tableName, String constraintName) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            connection.prepareStatement(
                """
                SELECT pg_get_constraintdef(con.oid) AS def
                  FROM pg_constraint con
                  JOIN pg_class cls ON cls.oid = con.conrelid
                  WHERE cls.relname = ? AND con.conname = ?
                """)) {
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

  private static String columnDefault(String tableName, String columnName) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            connection.prepareStatement(
                """
                SELECT column_default
                  FROM information_schema.columns
                  WHERE table_name = ? AND column_name = ?
                """)) {
      ps.setString(1, tableName);
      ps.setString(2, columnName);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString("column_default");
        }
        return null;
      }
    }
  }
}
