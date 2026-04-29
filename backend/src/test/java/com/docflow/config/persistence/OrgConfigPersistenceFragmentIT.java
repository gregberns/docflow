package com.docflow.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fragment-level mapping test: loads c1-org-config.sql directly into a fresh Postgres so the JPA
 * entities can round-trip rows ahead of C7.4's V1__init.sql assembly. When df-9c2.4 lands, this
 * test should be folded into the full V1-driven integration suite.
 */
@Testcontainers
@SpringBootTest(
    classes = OrgConfigPersistenceFragmentIT.JpaTestApp.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none"
    })
class OrgConfigPersistenceFragmentIT {

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

  @Test
  void roundTripsCompleteOrganizationGraph() {
    OrganizationEntity org =
        new OrganizationEntity("riverside-bistro", "Riverside Bistro", "icon-riverside", 0);
    organizationRepository.saveAndFlush(org);

    DocumentTypeEntity docType =
        new DocumentTypeEntity(
            "riverside-bistro",
            "invoice",
            "Invoice",
            "PDF",
            Map.of("fields", List.of(Map.of("name", "vendor", "type", "STRING"))));
    documentTypeRepository.saveAndFlush(docType);

    organizationDocTypeRepository.saveAndFlush(
        new OrganizationDocTypeEntity("riverside-bistro", "invoice", 0));

    workflowRepository.saveAndFlush(new WorkflowEntity("riverside-bistro", "invoice"));

    StageEntity review =
        new StageEntity(
            "riverside-bistro",
            "invoice",
            "Review",
            "Review",
            "REVIEW",
            "AWAITING_REVIEW",
            null,
            0);
    StageEntity filed =
        new StageEntity(
            "riverside-bistro", "invoice", "Filed", "Filed", "TERMINAL", "FILED", null, 1);
    stageRepository.saveAndFlush(review);
    stageRepository.saveAndFlush(filed);

    transitionRepository.saveAndFlush(
        new TransitionEntity(
            UUID.randomUUID(),
            new TransitionEntity.TransitionKey(
                "riverside-bistro", "invoice", "Review", "Filed", "APPROVE"),
            null,
            0));

    assertThat(organizationRepository.findAll()).hasSize(1);
    assertThat(documentTypeRepository.findByOrganizationId("riverside-bistro")).hasSize(1);
    assertThat(workflowRepository.findAll()).hasSize(1);
    assertThat(
            organizationDocTypeRepository.findByOrganizationIdOrderByOrdinalAsc("riverside-bistro"))
        .hasSize(1);
    assertThat(
            stageRepository.findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(
                "riverside-bistro", "invoice"))
        .extracting(StageEntity::getId)
        .containsExactly("Review", "Filed");
    assertThat(
            transitionRepository.findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(
                "riverside-bistro", "invoice"))
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.getFromStage()).isEqualTo("Review");
              assertThat(t.getToStage()).isEqualTo("Filed");
              assertThat(t.getAction()).isEqualTo("APPROVE");
              assertThat(t.getGuardField()).isNull();
            });

    DocumentTypeEntity loaded =
        documentTypeRepository
            .findById(new DocumentTypeId("riverside-bistro", "invoice"))
            .orElseThrow();
    assertThat(loaded.getInputModality()).isEqualTo("PDF");
    assertThat(loaded.getFieldSchema()).containsKey("fields");
  }

  @SpringBootApplication
  static class JpaTestApp {}
}
