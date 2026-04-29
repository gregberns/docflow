package com.docflow.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.config.AppConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(
    classes = SeedManifestTest.SeedTestApp.class,
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
      "docflow.config.seed-on-boot=true",
      "docflow.config.seed-resource-path=classpath:seed/"
    })
class SeedManifestTest {

  private static @TempDir Path STORAGE_ROOT;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/migration/V1__init.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("docflow.storage.storage-root", () -> STORAGE_ROOT.toString());
  }

  @Autowired private DataSource dataSource;
  @Autowired private SeedDataLoader seedDataLoader;

  @Test
  @Order(1)
  void seededDocumentsMatchManifestExactly() throws IOException {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<SeedManifestEntry> manifest = readManifest();

    Long documentCount = jdbc.queryForObject("SELECT count(*) FROM documents", Long.class);
    assertThat(documentCount).as("documents row count").isEqualTo((long) manifest.size());

    Long storedCount = jdbc.queryForObject("SELECT count(*) FROM stored_documents", Long.class);
    assertThat(storedCount).as("stored_documents row count").isEqualTo((long) manifest.size());

    Long processingCount =
        jdbc.queryForObject("SELECT count(*) FROM processing_documents", Long.class);
    assertThat(processingCount).as("processing_documents must be empty for seeded data").isZero();

    Long workflowInstanceCount =
        jdbc.queryForObject("SELECT count(*) FROM workflow_instances", Long.class);
    assertThat(workflowInstanceCount)
        .as("workflow_instances row count")
        .isEqualTo((long) manifest.size());

    for (SeedManifestEntry expected : manifest) {
      Map<String, Object> joinRow =
          jdbc.queryForMap(
              "SELECT d.organization_id, d.detected_document_type, d.extracted_fields, "
                  + "d.reextraction_status, "
                  + "wi.current_stage_id, wi.current_status, "
                  + "wi.workflow_origin_stage, wi.flag_comment "
                  + "FROM documents d "
                  + "JOIN stored_documents s ON s.id = d.stored_document_id "
                  + "JOIN workflow_instances wi ON wi.document_id = d.id "
                  + "WHERE s.organization_id = ? AND s.source_filename = ?",
              expected.organizationId(),
              expected.path());

      assertThat(joinRow.get("organization_id")).isEqualTo(expected.organizationId());
      assertThat(joinRow.get("detected_document_type")).isEqualTo(expected.documentType());
      assertThat(joinRow.get("reextraction_status")).isEqualTo("NONE");
      assertThat(joinRow.get("current_stage_id"))
          .as("seeded WorkflowInstance.currentStageId for %s", expected.path())
          .isEqualTo(reviewStageIdFor(jdbc, expected));
      assertThat(joinRow.get("current_status")).isEqualTo("AWAITING_REVIEW");
      assertThat(joinRow.get("workflow_origin_stage")).isNull();
      assertThat(joinRow.get("flag_comment")).isNull();

      Map<String, Object> persistedFields = parseJsonb(joinRow.get("extracted_fields"));
      assertThat(persistedFields).isEqualTo(expected.extractedFields());
    }
  }

  @Test
  @Order(2)
  void reSeedingIsIdempotent() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    long documentsBefore = countOrZero(jdbc, "documents");
    long storedBefore = countOrZero(jdbc, "stored_documents");
    long workflowsBefore = countOrZero(jdbc, "workflow_instances");

    seedDataLoader.seedOnReady();
    seedDataLoader.seedOnReady();

    assertThat(countOrZero(jdbc, "documents")).isEqualTo(documentsBefore);
    assertThat(countOrZero(jdbc, "stored_documents")).isEqualTo(storedBefore);
    assertThat(countOrZero(jdbc, "workflow_instances")).isEqualTo(workflowsBefore);
    assertThat(countOrZero(jdbc, "processing_documents")).isZero();
  }

  private static List<SeedManifestEntry> readManifest() throws IOException {
    ObjectMapper yamlMapper = YAMLMapper.builder().build();
    try (InputStream in =
        SeedManifestTest.class.getClassLoader().getResourceAsStream("seed/manifest.yaml")) {
      if (in == null) {
        throw new IOException("Missing classpath resource: seed/manifest.yaml");
      }
      return yamlMapper.readValue(in, new TypeReference<List<SeedManifestEntry>>() {});
    }
  }

  private static String reviewStageIdFor(JdbcTemplate jdbc, SeedManifestEntry entry) {
    return jdbc.queryForObject(
        "SELECT id FROM stages "
            + "WHERE organization_id = ? AND document_type_id = ? AND kind = 'REVIEW' "
            + "ORDER BY ordinal ASC LIMIT 1",
        String.class,
        entry.organizationId(),
        entry.documentType());
  }

  private static Map<String, Object> parseJsonb(Object dbValue) {
    String json = dbValue == null ? "{}" : dbValue.toString();
    ObjectMapper jsonMapper = tools.jackson.databind.json.JsonMapper.builder().build();
    return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
  }

  private static long countOrZero(JdbcTemplate jdbc, String table) {
    Long c = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    return c == null ? 0L : c;
  }

  @SpringBootApplication(
      scanBasePackages = {
        "com.docflow.config",
        "com.docflow.platform",
        "com.docflow.ingestion",
        "com.docflow.workflow",
        "com.docflow.document"
      })
  @EntityScan({"com.docflow.config.persistence", "com.docflow.ingestion.internal"})
  @EnableJpaRepositories({"com.docflow.config.persistence", "com.docflow.ingestion.internal"})
  @ConfigurationPropertiesScan("com.docflow.config")
  static class SeedTestApp {

    @Bean
    AppConfig.Storage appStorage(AppConfig appConfig) {
      return appConfig.storage();
    }
  }
}
