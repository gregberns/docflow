package com.docflow.config.org.seeder;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.config.persistence.DocumentTypeRepository;
import com.docflow.config.persistence.OrganizationDocTypeRepository;
import com.docflow.config.persistence.OrganizationRepository;
import com.docflow.config.persistence.StageEntity;
import com.docflow.config.persistence.StageRepository;
import com.docflow.config.persistence.TransitionEntity;
import com.docflow.config.persistence.TransitionRepository;
import com.docflow.config.persistence.WorkflowRepository;
import java.util.List;
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
    classes = OrgConfigSeederIT.SeederTestApp.class,
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
class OrgConfigSeederIT {

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

  @Autowired private OrgConfigSeeder seeder;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OrganizationDocTypeRepository organizationDocTypeRepository;
  @Autowired private DocumentTypeRepository documentTypeRepository;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private StageRepository stageRepository;
  @Autowired private TransitionRepository transitionRepository;

  @Test
  void acS1_emptyDbSeedsProductionRowCounts() {
    assertThat(organizationRepository.count()).as("organizations").isEqualTo(3L);
    assertThat(documentTypeRepository.count()).as("document_types").isEqualTo(9L);
    assertThat(organizationDocTypeRepository.count()).as("organization_doc_types").isEqualTo(9L);
    assertThat(workflowRepository.count()).as("workflows").isEqualTo(9L);
    assertThat(stageRepository.count()).as("stages").isGreaterThanOrEqualTo(27L);
    assertThat(transitionRepository.count()).as("transitions").isGreaterThanOrEqualTo(36L);
  }

  @Test
  void acS1_lienWaiverSeedsTwoGuardedReviewTransitions() {
    List<TransitionEntity> transitions =
        transitionRepository.findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(
            "ironworks-construction", "lien-waiver");

    assertThat(transitions)
        .filteredOn(t -> "Review".equals(t.getFromStage()) && "APPROVE".equals(t.getAction()))
        .hasSize(2)
        .allSatisfy(
            t -> {
              assertThat(t.getGuardField()).isEqualTo("waiverType");
              assertThat(t.getGuardValue()).isEqualTo("unconditional");
              assertThat(t.getGuardOp()).isIn("EQ", "NEQ");
            });
  }

  @Test
  void acS1_stagesAreOrderedByOrdinal() {
    List<StageEntity> stages =
        stageRepository.findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(
            "ironworks-construction", "lien-waiver");
    assertThat(stages)
        .extracting(StageEntity::getId)
        .containsExactly("Review", "Project Manager Approval", "Filed", "Rejected");
  }

  @Test
  void acS2_reSeedingIsIdempotent() {
    long orgs = organizationRepository.count();
    long docTypes = documentTypeRepository.count();
    long workflows = workflowRepository.count();
    long stages = stageRepository.count();
    long transitions = transitionRepository.count();

    seeder.seedOnReady();
    seeder.seedOnReady();

    assertThat(organizationRepository.count()).isEqualTo(orgs);
    assertThat(documentTypeRepository.count()).isEqualTo(docTypes);
    assertThat(workflowRepository.count()).isEqualTo(workflows);
    assertThat(stageRepository.count()).isEqualTo(stages);
    assertThat(transitionRepository.count()).isEqualTo(transitions);
  }

  @SpringBootApplication(scanBasePackages = "com.docflow.config")
  @EntityScan("com.docflow.config.persistence")
  @EnableJpaRepositories("com.docflow.config.persistence")
  @ConfigurationPropertiesScan("com.docflow.config")
  static class SeederTestApp {}
}
