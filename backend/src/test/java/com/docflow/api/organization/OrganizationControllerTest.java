package com.docflow.api.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    classes = OrganizationControllerTest.ApiTestApp.class,
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
class OrganizationControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withInitScripts(
              "db/migration/V1__init.sql",
              "db/migration/V3__add_updated_at_to_processing_documents.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JdbcTemplate jdbcTemplate;

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
  void list_returnsAllOrgsWithZeroCountsWhenNoRuntimeData() throws Exception {
    mockMvc
        .perform(get("/api/organizations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").exists())
        .andExpect(jsonPath("$[0].name").exists())
        .andExpect(jsonPath("$[0].icon").exists())
        .andExpect(jsonPath("$[0].docTypes").isArray())
        .andExpect(jsonPath("$[0].inProgressCount").value(0))
        .andExpect(jsonPath("$[0].filedCount").value(0));
  }

  @Test
  void list_inProgressCount_matchesProcessingDocsWithoutDocuments() throws Exception {
    String orgId = "pinnacle-legal";
    seedProcessingOnly(orgId, 2);
    seedProcessingWithFiledDocument(orgId, "invoice");

    String body =
        mockMvc
            .perform(get("/api/organizations"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long inProgress = extractCount(body, orgId, "inProgressCount");
    long filed = extractCount(body, orgId, "filedCount");

    assertThat(inProgress)
        .as("inProgressCount = processing rows w/o matching document")
        .isEqualTo(2L);
    assertThat(filed).as("filedCount = workflow_instances FILED").isEqualTo(1L);
  }

  @Test
  void list_filedCount_onlyCountsFiledStatus() throws Exception {
    String orgId = "riverside-bistro";
    seedProcessingWithFiledDocument(orgId, "invoice");
    seedProcessingWithFiledDocument(orgId, "invoice");
    seedProcessingWithReviewDocument(orgId, "invoice");

    String body =
        mockMvc
            .perform(get("/api/organizations"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(extractCount(body, orgId, "filedCount")).isEqualTo(2L);
    assertThat(extractCount(body, orgId, "inProgressCount")).isEqualTo(0L);
  }

  @Test
  void detail_returnsWorkflowsAndFieldSchemas() throws Exception {
    mockMvc
        .perform(get("/api/organizations/pinnacle-legal"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("pinnacle-legal"))
        .andExpect(jsonPath("$.name").exists())
        .andExpect(jsonPath("$.icon").exists())
        .andExpect(jsonPath("$.docTypes").isArray())
        .andExpect(jsonPath("$.docTypes.length()").value(3))
        .andExpect(jsonPath("$.workflows").isArray())
        .andExpect(jsonPath("$.workflows.length()").value(3))
        .andExpect(jsonPath("$.workflows[0].documentTypeId").exists())
        .andExpect(jsonPath("$.workflows[0].stages").isArray())
        .andExpect(jsonPath("$.workflows[0].stages[0].id").exists())
        .andExpect(jsonPath("$.workflows[0].stages[0].canonicalStatus").exists())
        .andExpect(jsonPath("$.workflows[0].transitions").isArray())
        .andExpect(jsonPath("$.fieldSchemas").exists())
        .andExpect(jsonPath("$.fieldSchemas.invoice").isArray())
        .andExpect(jsonPath("$.fieldSchemas.invoice[0].name").exists())
        .andExpect(jsonPath("$.fieldSchemas.invoice[0].type").exists());
  }

  @Test
  void detail_fieldSchemas_includeFormatForCurrencyDeclaredFields() throws Exception {
    String body =
        mockMvc
            .perform(get("/api/organizations/pinnacle-legal"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("\"format\":\"currency:USD\"");
    int retainerIdx = body.indexOf("\"retainer-agreement\"");
    assertThat(retainerIdx).isPositive();
    int amountIdx = body.indexOf("\"retainerAmount\"", retainerIdx);
    int formatIdx = body.indexOf("\"format\":\"currency:USD\"", amountIdx);
    int nextFieldIdx = body.indexOf("\"name\":", amountIdx);
    assertThat(formatIdx).as("format appears for retainerAmount").isPositive();
    assertThat(formatIdx).isLessThan(nextFieldIdx);
  }

  @Test
  void detail_unknownOrgId_returns404Problem() throws Exception {
    mockMvc
        .perform(get("/api/organizations/does-not-exist"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_ORGANIZATION"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(
            jsonPath("$.message").value(org.hamcrest.Matchers.containsString("does-not-exist")));
  }

  private void seedProcessingOnly(String orgId, int count) {
    for (int i = 0; i < count; i++) {
      UUID storedId = insertStoredDocument(orgId);
      insertProcessingDocument(orgId, storedId);
    }
  }

  private void seedProcessingWithFiledDocument(String orgId, String docType) {
    seedProcessingWithDocument(orgId, docType, "FILED", "Filed");
  }

  private void seedProcessingWithReviewDocument(String orgId, String docType) {
    String stageId = stageIdForOrg(orgId);
    seedProcessingWithDocument(orgId, docType, "AWAITING_REVIEW", stageId);
  }

  private String stageIdForOrg(String orgId) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM stages WHERE organization_id = ? AND canonical_status = 'AWAITING_REVIEW' "
            + "ORDER BY ordinal ASC LIMIT 1",
        String.class,
        orgId);
  }

  private void seedProcessingWithDocument(
      String orgId, String docType, String currentStatus, String stageId) {
    UUID storedId = insertStoredDocument(orgId);
    insertProcessingDocument(orgId, storedId);
    UUID documentId = insertDocument(orgId, storedId, docType);
    insertWorkflowInstance(orgId, docType, documentId, stageId, currentStatus);
  }

  private UUID insertStoredDocument(String orgId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO stored_documents "
            + "(id, organization_id, uploaded_at, source_filename, mime_type, storage_path) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        id,
        orgId,
        OffsetDateTime.now(ZoneOffset.UTC),
        "test-" + id + ".pdf",
        "application/pdf",
        "/tmp/" + id);
    return id;
  }

  private void insertProcessingDocument(String orgId, UUID storedId) {
    jdbcTemplate.update(
        "INSERT INTO processing_documents "
            + "(id, stored_document_id, organization_id, current_step, raw_text, last_error) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        storedId,
        orgId,
        "EXTRACTING",
        null,
        null);
  }

  private UUID insertDocument(String orgId, UUID storedId, String docType) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO documents "
            + "(id, stored_document_id, organization_id, detected_document_type, "
            + "extracted_fields, raw_text, processed_at, reextraction_status) "
            + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
        id,
        storedId,
        orgId,
        docType,
        "{}",
        null,
        OffsetDateTime.now(ZoneOffset.UTC),
        "NONE");
    return id;
  }

  private void insertWorkflowInstance(
      String orgId, String docType, UUID documentId, String stageId, String currentStatus) {
    jdbcTemplate.update(
        "INSERT INTO workflow_instances "
            + "(id, document_id, organization_id, document_type_id, current_stage_id, "
            + "current_status, workflow_origin_stage, flag_comment, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        documentId,
        orgId,
        docType,
        stageId,
        currentStatus,
        null,
        null,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static long extractCount(String json, String orgId, String field) {
    int orgIdx = json.indexOf("\"id\":\"" + orgId + "\"");
    if (orgIdx < 0) {
      throw new AssertionError("orgId not found: " + orgId);
    }
    int fieldIdx = json.indexOf("\"" + field + "\":", orgIdx);
    if (fieldIdx < 0) {
      throw new AssertionError("field not found after orgId: " + field);
    }
    int valStart = fieldIdx + field.length() + 3;
    int valEnd = valStart;
    while (valEnd < json.length() && Character.isDigit(json.charAt(valEnd))) {
      valEnd++;
    }
    return Long.parseLong(json.substring(valStart, valEnd));
  }

  @SpringBootApplication(
      scanBasePackages = {"com.docflow.config", "com.docflow.api", "com.docflow.platform"})
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
