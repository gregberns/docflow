package com.docflow.c3.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.Application;
import com.docflow.config.catalog.OrganizationCatalog;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression test for df-xqh: with {@code seedOnBoot=false} the full {@link Application} context
 * must still boot cleanly. The bug was that {@link
 * com.docflow.config.catalog.OrganizationCatalogImpl#loadOnReady}, {@link
 * com.docflow.config.catalog.DocumentTypeCatalogImpl#loadOnReady}, and {@link
 * PromptLibrary#validateOnReady} all used {@code @Order(LOWEST_PRECEDENCE)}. Spring tie-broke same-
 * precedence listeners in a way that, with seeding disabled, scheduled {@code PromptLibrary} ahead
 * of the catalogs — causing it to read an unloaded snapshot and fail boot with {@code
 * IllegalStateException("catalog not loaded")}.
 *
 * <p>This test boots the full {@link Application} against an empty Postgres (schema preloaded via
 * Testcontainers init script, Flyway disabled to keep the test self-contained) and asserts {@link
 * PromptLibrary} successfully validated. If the ordering regresses, {@code validateOnReady} throws
 * during boot and this test fails at context startup.
 */
@Testcontainers
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "docflow.config.seed-on-boot=false",
      "docflow.config.seed-resource-path=classpath:seed/",
      "docflow.llm.model-id=claude-sonnet-4-6",
      "docflow.llm.api-key=sk-ant-test",
      "docflow.llm.request-timeout=PT60S",
      "docflow.llm.eval.report-path=eval/reports/latest.md",
      "docflow.database.url=ignored",
      "docflow.database.user=ignored",
      "docflow.database.password=ignored"
    })
class PromptLibraryBootOrderingIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("db/migration/V1__init.sql");

  @TempDir static Path storageRoot;

  @DynamicPropertySource
  static void dynamicProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("docflow.storage.storage-root", () -> storageRoot.toAbsolutePath().toString());
  }

  @Autowired private PromptLibrary promptLibrary;
  @Autowired private OrganizationCatalog organizationCatalog;

  @Test
  void contextBootsAndPromptLibraryValidatesEvenWithSeedDisabled() {
    assertThat(organizationCatalog.listOrganizations())
        .as("seedOnBoot=false against empty Postgres yields an empty catalog snapshot")
        .isEmpty();

    assertThat(promptLibrary.getClassify().raw())
        .as(
            "PromptLibrary.validateOnReady ran successfully — it loaded classify.txt without"
                + " crashing on the OrganizationCatalog snapshot")
        .contains("{{ALLOWED_DOC_TYPES}}");
  }
}
