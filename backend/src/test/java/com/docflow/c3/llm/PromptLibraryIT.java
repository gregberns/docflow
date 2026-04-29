package com.docflow.c3.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.config.catalog.OrganizationView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    classes = PromptLibraryIT.PromptLibraryIntegrationApp.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none",
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
class PromptLibraryIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withInitScript("db/migration/fragments/c1-org-config.sql");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private PromptLibrary promptLibrary;
  @Autowired private OrganizationCatalog organizationCatalog;

  @Test
  void allProductionAllowedDocTypesHaveExtractPrompt() {
    assertThat(promptLibrary.getClassify().raw()).contains("{{ALLOWED_DOC_TYPES}}");

    List<OrganizationView> orgs = organizationCatalog.listOrganizations();
    assertThat(orgs).hasSize(3);

    for (OrganizationView org : orgs) {
      for (String docTypeId : organizationCatalog.getAllowedDocTypes(org.id())) {
        PromptTemplate template = promptLibrary.getExtract(docTypeId);
        assertThat(template).as("prompt for %s", docTypeId).isNotNull();
        assertThat(template.raw()).as("non-empty body for %s", docTypeId).isNotBlank();
      }
    }
  }

  @Test
  void classifyTemplateRendersAllowedDocTypesPlaceholder() {
    String rendered =
        promptLibrary
            .getClassify()
            .render(Map.of("ALLOWED_DOC_TYPES", "invoice, receipt, expense-report"));
    assertThat(rendered).contains("invoice, receipt, expense-report");
    assertThat(rendered).doesNotContain("{{ALLOWED_DOC_TYPES}}");
  }

  @SpringBootApplication(scanBasePackages = {"com.docflow.config", "com.docflow.c3.llm"})
  @EntityScan("com.docflow.config.persistence")
  @EnableJpaRepositories("com.docflow.config.persistence")
  @ConfigurationPropertiesScan("com.docflow.config")
  static class PromptLibraryIntegrationApp {}
}
