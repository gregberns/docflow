package com.docflow.api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    classes = DashboardControllerIT.ApiTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "docflow.llm.model-id=claude-sonnet-4-6",
      "docflow.llm.api-key=sk-ant-test",
      "docflow.llm.request-timeout=PT60S",
      "docflow.llm.eval.report-path=eval/reports/latest.md",
      "docflow.storage.storage-root=/tmp/docflow",
      "docflow.database.url=ignored",
      "docflow.database.user=ignored",
      "docflow.database.password=ignored",
      "docflow.config.seed-on-boot=true",
      "docflow.config.seed-resource-path=classpath:seed/"
    })
class DashboardControllerIT {

  private static final String ORG_ID = "pinnacle-legal";
  private static final String DOC_TYPE = "invoice";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/migration/V1__init.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DashboardRepository wiredRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    jdbcTemplate.update("DELETE FROM workflow_instances");
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update("DELETE FROM processing_documents");
    jdbcTemplate.update("DELETE FROM stored_documents");
  }

  @Test
  void wiredRepositoryIsTheRealJdbcImpl_notTheStub() {
    assertThat(wiredRepository).isInstanceOf(JdbcDashboardRepository.class);
  }

  @Test
  void dashboard_returnsNonEmptyArraysWhenDataPresent() throws Exception {
    UUID storedProc = insertStoredDocument(ORG_ID);
    insertProcessingDocument(ORG_ID, storedProc, "CLASSIFYING");

    UUID storedDoc = insertStoredDocument(ORG_ID);
    UUID documentId = insertDocument(ORG_ID, storedDoc, DOC_TYPE);
    String stageId = stageIdForOrg(ORG_ID);
    insertWorkflowInstance(ORG_ID, DOC_TYPE, documentId, stageId, "AWAITING_REVIEW");

    mockMvc
        .perform(get("/api/organizations/{orgId}/documents", ORG_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processing").isArray())
        .andExpect(jsonPath("$.processing.length()").value(1))
        .andExpect(jsonPath("$.documents").isArray())
        .andExpect(jsonPath("$.documents.length()").value(1))
        .andExpect(jsonPath("$.documents[0].documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.documents[0].currentStatus").value("AWAITING_REVIEW"))
        .andExpect(jsonPath("$.documents[0].detectedDocumentType").value(DOC_TYPE))
        .andExpect(jsonPath("$.stats.awaitingReview").value(1))
        .andExpect(jsonPath("$.stats.inProgress").value(1));
  }

  @Test
  void dashboard_emptyResultsWhenNoData() throws Exception {
    mockMvc
        .perform(get("/api/organizations/{orgId}/documents", ORG_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processing").isArray())
        .andExpect(jsonPath("$.processing.length()").value(0))
        .andExpect(jsonPath("$.documents").isArray())
        .andExpect(jsonPath("$.documents.length()").value(0))
        .andExpect(jsonPath("$.stats.inProgress").value(0))
        .andExpect(jsonPath("$.stats.awaitingReview").value(0));
  }

  private String stageIdForOrg(String orgId) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM stages WHERE organization_id = ? AND canonical_status = 'AWAITING_REVIEW' "
            + "ORDER BY ordinal ASC LIMIT 1",
        String.class,
        orgId);
  }

  private UUID insertStoredDocument(String orgId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO stored_documents "
            + "(id, organization_id, uploaded_at, source_filename, mime_type, storage_path) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        id,
        orgId,
        Timestamp.from(Instant.now()),
        "test-" + id + ".pdf",
        "application/pdf",
        "/tmp/" + id);
    return id;
  }

  private void insertProcessingDocument(String orgId, UUID storedId, String currentStep) {
    jdbcTemplate.update(
        "INSERT INTO processing_documents "
            + "(id, stored_document_id, organization_id, current_step, raw_text, last_error) "
            + "VALUES (?, ?, ?, ?, NULL, NULL)",
        UUID.randomUUID(),
        storedId,
        orgId,
        currentStep);
  }

  private UUID insertDocument(String orgId, UUID storedId, String docType) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO documents "
            + "(id, stored_document_id, organization_id, detected_document_type, "
            + "extracted_fields, raw_text, processed_at, reextraction_status) "
            + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 'NONE')",
        id,
        storedId,
        orgId,
        docType,
        "{\"vendor\":\"ACME\"}",
        "raw text",
        Timestamp.from(Instant.now()));
    return id;
  }

  private void insertWorkflowInstance(
      String orgId, String docType, UUID documentId, String stageId, String currentStatus) {
    jdbcTemplate.update(
        "INSERT INTO workflow_instances "
            + "(id, document_id, organization_id, document_type_id, current_stage_id, "
            + "current_status, workflow_origin_stage, flag_comment, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
        UUID.randomUUID(),
        documentId,
        orgId,
        docType,
        stageId,
        currentStatus,
        Timestamp.from(Instant.now()));
  }

  @SpringBootApplication(scanBasePackages = {"com.docflow.config", "com.docflow.api"})
  @ComponentScan(
      basePackages = {"com.docflow.config", "com.docflow.api", "com.docflow.platform"},
      excludeFilters = {
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.docflow\\..*Test\\$.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.docflow\\..*IT\\$.*"),
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.docflow\\.api\\.document\\..*")
      })
  @EntityScan("com.docflow.config.persistence")
  @EnableJpaRepositories("com.docflow.config.persistence")
  @ConfigurationPropertiesScan("com.docflow.config")
  static class ApiTestApp {}
}
