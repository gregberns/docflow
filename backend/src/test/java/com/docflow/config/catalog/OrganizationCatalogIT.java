package com.docflow.config.catalog;

import static org.assertj.core.api.Assertions.assertThat;

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
    classes = OrganizationCatalogIT.SeederTestApp.class,
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
class OrganizationCatalogIT {

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

  @Autowired private OrganizationCatalog organizationCatalog;
  @Autowired private DocumentTypeCatalog documentTypeCatalog;
  @Autowired private WorkflowCatalog workflowCatalog;

  @Test
  void acC1_listOrganizationsReturnsThreeEntriesInYamlOrder() {
    List<OrganizationView> orgs = organizationCatalog.listOrganizations();
    assertThat(orgs)
        .extracting(OrganizationView::id)
        .containsExactly("riverside-bistro", "pinnacle-legal", "ironworks-construction");
  }

  @Test
  void acC2_getOrganizationReturnsEmptyForUnknownId() {
    Optional<OrganizationView> result = organizationCatalog.getOrganization("nope");
    assertThat(result).isEmpty();
  }

  @Test
  void acC3_getDocumentTypeSchemaForRetainerAgreementReturnsSevenFlatFields() {
    Optional<DocumentTypeSchemaView> schema =
        documentTypeCatalog.getDocumentTypeSchema("pinnacle-legal", "retainer-agreement");

    assertThat(schema).isPresent();
    DocumentTypeSchemaView view = schema.orElseThrow();
    assertThat(view.fields()).hasSize(7);
    assertThat(view.fields()).allSatisfy(field -> assertThat(field.itemFields()).isNull());
    assertThat(view.fields())
        .extracting(FieldView::name)
        .containsExactly(
            "clientName",
            "matterType",
            "hourlyRate",
            "retainerAmount",
            "effectiveDate",
            "termLength",
            "scope");
  }

  @Test
  void acC4_listDocumentTypesReturnsThreeEntriesInDocumentTypeIdsOrder() {
    List<DocumentTypeSchemaView> docTypes =
        documentTypeCatalog.listDocumentTypes("riverside-bistro");
    assertThat(docTypes)
        .extracting(DocumentTypeSchemaView::id)
        .containsExactly("invoice", "receipt", "expense-report");
  }

  @Test
  void acC5_getWorkflowForLienWaiverReturnsBothGuardedReviewApproveTransitions() {
    Optional<WorkflowView> workflow =
        workflowCatalog.getWorkflow("ironworks-construction", "lien-waiver");

    assertThat(workflow).isPresent();
    List<TransitionView> reviewApproves =
        workflow.orElseThrow().transitions().stream()
            .filter(t -> "Review".equals(t.fromStage()) && "APPROVE".equals(t.action()))
            .toList();

    assertThat(reviewApproves).hasSize(2);
    assertThat(reviewApproves)
        .allSatisfy(
            t -> {
              assertThat(t.guard()).isNotNull();
              assertThat(t.guard().field()).isEqualTo("waiverType");
              assertThat(t.guard().value()).isEqualTo("unconditional");
            });
    assertThat(reviewApproves)
        .extracting(t -> t.guard().op())
        .containsExactlyInAnyOrder("EQ", "NEQ");
  }

  @Test
  void acC6_getAllowedDocTypesReturnsRiversideListInOrder() {
    List<String> allowed = organizationCatalog.getAllowedDocTypes("riverside-bistro");
    assertThat(allowed).containsExactly("invoice", "receipt", "expense-report");
  }

  @Test
  void formatPropagatesFromYamlThroughJsonbToCatalogView() {
    DocumentTypeSchemaView retainer =
        documentTypeCatalog
            .getDocumentTypeSchema("pinnacle-legal", "retainer-agreement")
            .orElseThrow();

    FieldView retainerAmount =
        retainer.fields().stream()
            .filter(f -> "retainerAmount".equals(f.name()))
            .findFirst()
            .orElseThrow();
    assertThat(retainerAmount.format()).isEqualTo("currency:USD");

    FieldView clientName =
        retainer.fields().stream()
            .filter(f -> "clientName".equals(f.name()))
            .findFirst()
            .orElseThrow();
    assertThat(clientName.format()).isNull();

    DocumentTypeSchemaView bistroInvoice =
        documentTypeCatalog.getDocumentTypeSchema("riverside-bistro", "invoice").orElseThrow();
    FieldView lineItems =
        bistroInvoice.fields().stream()
            .filter(f -> "lineItems".equals(f.name()))
            .findFirst()
            .orElseThrow();
    assertThat(lineItems.itemFields()).isNotNull();
    FieldView lineTotal =
        lineItems.itemFields().stream()
            .filter(f -> "total".equals(f.name()))
            .findFirst()
            .orElseThrow();
    assertThat(lineTotal.format()).isEqualTo("currency:USD");
    FieldView quantity =
        lineItems.itemFields().stream()
            .filter(f -> "quantity".equals(f.name()))
            .findFirst()
            .orElseThrow();
    assertThat(quantity.format()).isNull();
  }

  @SpringBootApplication(scanBasePackages = "com.docflow.config")
  @EntityScan("com.docflow.config.persistence")
  @EnableJpaRepositories("com.docflow.config.persistence")
  @ConfigurationPropertiesScan("com.docflow.config")
  static class SeederTestApp {}
}
