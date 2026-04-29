package com.docflow.c3.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.docflow.c3.audit.LlmCallAuditWriter;
import com.docflow.c3.llm.LlmClassifier;
import com.docflow.c3.llm.LlmExtractor;
import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.platform.DocumentEventBus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest(
    classes = PipelineSmokeIT.PipelineSmokeApp.class,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none",
      "docflow.llm.model-id=claude-sonnet-4-6",
      "docflow.llm.request-timeout=PT120S",
      "docflow.llm.eval.report-path=eval/reports/latest.md",
      "docflow.storage.storage-root=/tmp/docflow",
      "docflow.database.url=ignored",
      "docflow.database.user=ignored",
      "docflow.database.password=ignored",
      "docflow.config.seed-on-boot=true",
      "docflow.config.seed-resource-path=classpath:seed/"
    })
class PipelineSmokeIT {

  private static final String ORG_ID = "riverside-bistro";
  private static final String EXPECTED_DOC_TYPE = "invoice";
  private static final String SAMPLE_RELATIVE_PATH =
      "riverside-bistro/invoices/artisanal_ice_cube_march_2024.pdf";

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

  @Autowired private LlmClassifier classifier;
  @Autowired private LlmExtractor extractor;

  @Test
  void classifyAndExtractRoundTripsAgainstLiveAnthropicApi() throws IOException {
    String rawText = extractTextFromSample();
    assertThat(rawText).contains("Artisanal Ice Cube");

    UUID storedDocumentId = UUID.randomUUID();
    UUID processingDocumentId = UUID.randomUUID();

    LlmClassifier.ClassifyResult classified =
        classifier.classify(storedDocumentId, processingDocumentId, ORG_ID, rawText);
    assertThat(classified.detectedDocumentType()).isEqualTo(EXPECTED_DOC_TYPE);

    Map<String, Object> fields =
        extractor.extractFields(
            storedDocumentId,
            processingDocumentId,
            ORG_ID,
            classified.detectedDocumentType(),
            rawText);

    assertThat(fields).isNotEmpty();
    assertThat(fields).containsKeys("vendor", "invoiceNumber", "totalAmount", "lineItems");

    Object vendor = fields.get("vendor");
    assertThat(vendor).isInstanceOf(String.class);
    assertThat(((String) vendor)).containsIgnoringCase("Artisanal Ice Cube");

    Object invoiceNumber = fields.get("invoiceNumber");
    assertThat(invoiceNumber).isInstanceOf(String.class);
    assertThat(((String) invoiceNumber)).matches("(?i).*INV.*AICC.*0182.*");

    Object totalAmount = fields.get("totalAmount");
    assertThat(totalAmount).isNotNull();
    assertThat(String.valueOf(totalAmount)).contains("1178");

    Object lineItems = fields.get("lineItems");
    assertThat(lineItems).isInstanceOf(java.util.List.class);
    assertThat((java.util.List<?>) lineItems).isNotEmpty();
  }

  private static String extractTextFromSample() throws IOException {
    String[] candidates = {
      "problem-statement/samples/" + SAMPLE_RELATIVE_PATH,
      "../problem-statement/samples/" + SAMPLE_RELATIVE_PATH
    };
    byte[] bytes = null;
    for (String candidate : candidates) {
      Path p = Paths.get(candidate);
      if (Files.exists(p)) {
        bytes = Files.readAllBytes(p);
        break;
      }
    }
    if (bytes == null) {
      throw new IOException("sample PDF not found at any candidate path: " + SAMPLE_RELATIVE_PATH);
    }
    try (PDDocument document = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(document);
    }
  }

  @SpringBootApplication(scanBasePackages = {"com.docflow.config", "com.docflow.c3.llm"})
  @EntityScan("com.docflow.config.persistence")
  @EnableJpaRepositories("com.docflow.config.persistence")
  @ConfigurationPropertiesScan("com.docflow.config")
  static class PipelineSmokeApp {

    @Bean
    LlmCallAuditWriter llmCallAuditWriter() {
      return mock(LlmCallAuditWriter.class);
    }

    @Bean
    DocumentReader documentReader() {
      return mock(DocumentReader.class);
    }

    @Bean
    DocumentWriter documentWriter() {
      return mock(DocumentWriter.class);
    }

    @Bean
    DocumentEventBus documentEventBus() {
      return mock(DocumentEventBus.class);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
