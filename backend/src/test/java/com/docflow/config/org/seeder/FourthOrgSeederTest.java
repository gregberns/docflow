package com.docflow.config.org.seeder;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.config.catalog.DocumentTypeCatalog;
import com.docflow.config.catalog.DocumentTypeSchemaView;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.config.catalog.OrganizationView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.config.persistence.DocumentTypeRepository;
import com.docflow.config.persistence.OrganizationDocTypeRepository;
import com.docflow.config.persistence.OrganizationRepository;
import com.docflow.config.persistence.StageRepository;
import com.docflow.config.persistence.TransitionRepository;
import com.docflow.config.persistence.WorkflowRepository;
import java.util.List;
import java.util.Optional;
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
    classes = FourthOrgSeederTest.SeederTestApp.class,
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
      "docflow.config.seed-resource-path=classpath:seed-fourth-org/"
    })
class FourthOrgSeederTest {

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

  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OrganizationDocTypeRepository organizationDocTypeRepository;
  @Autowired private DocumentTypeRepository documentTypeRepository;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private StageRepository stageRepository;
  @Autowired private TransitionRepository transitionRepository;
  @Autowired private OrganizationCatalog organizationCatalog;
  @Autowired private DocumentTypeCatalog documentTypeCatalog;
  @Autowired private WorkflowCatalog workflowCatalog;

  @Test
  void acE1_seederLoadsFourthOrgBundle_persistsExpectedRowCounts() {
    assertThat(organizationRepository.count()).as("organizations").isEqualTo(4L);
    assertThat(documentTypeRepository.count()).as("document_types").isEqualTo(10L);
    assertThat(organizationDocTypeRepository.count()).as("organization_doc_types").isEqualTo(10L);
    assertThat(workflowRepository.count()).as("workflows").isEqualTo(10L);
    assertThat(stageRepository.count()).as("stages").isEqualTo(43L);
    assertThat(transitionRepository.count()).as("transitions").isEqualTo(47L);
  }

  @Test
  void acE1_organizationCatalogReturnsFourthOrgWithoutProductionSourceChange() {
    List<OrganizationView> orgs = organizationCatalog.listOrganizations();
    assertThat(orgs).hasSize(4);
    assertThat(orgs)
        .extracting(OrganizationView::id)
        .containsExactlyInAnyOrder(
            "riverside-bistro", "pinnacle-legal", "ironworks-construction", "municipal-clerk");

    Optional<OrganizationView> fetched = organizationCatalog.getOrganization("municipal-clerk");
    assertThat(fetched).isPresent();
    assertThat(fetched.orElseThrow().displayName()).isEqualTo("Municipal Clerk's Office");

    List<String> allowed = organizationCatalog.getAllowedDocTypes("municipal-clerk");
    assertThat(allowed).containsExactly("permit-application");
  }

  @Test
  void acE1_documentTypeCatalogExposesAuthoredFields() {
    Optional<DocumentTypeSchemaView> schema =
        documentTypeCatalog.getDocumentTypeSchema("municipal-clerk", "permit-application");
    assertThat(schema).isPresent();
    DocumentTypeSchemaView view = schema.orElseThrow();
    assertThat(view.fields())
        .extracting(f -> f.name())
        .containsExactly("applicantName", "parcelNumber", "filingDate", "feeAmount", "permitType");
  }

  @Test
  void acE1_workflowCatalogExposesAuthoredStagesAndTransitions() {
    Optional<WorkflowView> workflow =
        workflowCatalog.getWorkflow("municipal-clerk", "permit-application");
    assertThat(workflow).isPresent();
    WorkflowView view = workflow.orElseThrow();
    assertThat(view.stages())
        .extracting(s -> s.id())
        .containsExactly("Review", "Filed", "Rejected");
    assertThat(view.transitions()).hasSize(2);
    assertThat(view.transitions())
        .extracting(t -> t.fromStage(), t -> t.action(), t -> t.toStage())
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("Review", "APPROVE", "Filed"),
            org.assertj.core.groups.Tuple.tuple("Review", "REJECT", "Rejected"));
  }

  @SpringBootApplication(scanBasePackages = "com.docflow.config")
  @EntityScan("com.docflow.config.persistence")
  @EnableJpaRepositories("com.docflow.config.persistence")
  @ConfigurationPropertiesScan("com.docflow.config")
  static class SeederTestApp {}
}
