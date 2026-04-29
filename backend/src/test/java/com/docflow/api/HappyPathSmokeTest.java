package com.docflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.docflow.Application;
import com.docflow.api.dto.DashboardResponse;
import com.docflow.api.dto.DocumentView;
import com.docflow.api.dto.UploadAccepted;
import com.docflow.workflow.WorkflowStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest(
    classes = Application.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "docflow.config.seed-on-boot=true",
      "docflow.config.seed-resource-path=classpath:seed/",
      "docflow.llm.model-id=claude-sonnet-4-6",
      "docflow.llm.request-timeout=PT120S",
      "docflow.llm.eval.report-path=eval/reports/latest.md",
      "docflow.database.url=ignored",
      "docflow.database.user=ignored",
      "docflow.database.password=ignored"
    })
class HappyPathSmokeTest {

  private static final String ORG_ID = "riverside-bistro";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir static Path storageRoot;

  @DynamicPropertySource
  static void dynamicProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("docflow.storage.storage-root", () -> storageRoot.toAbsolutePath().toString());
  }

  @LocalServerPort private int port;

  private final RestTemplate restTemplate = new RestTemplate();

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  @Test
  void uploadProcessReviewApprove_advancesDocumentOneStage() throws IOException {
    byte[] pdfBytes = loadSampleInvoiceBytes();

    UploadAccepted uploaded = uploadDocument(pdfBytes);
    assertThat(uploaded.storedDocumentId()).isNotNull();
    assertThat(uploaded.processingDocumentId()).isNotNull();

    UUID documentId = awaitDocumentReachesAwaitingReview();

    DocumentView approved = approveDocument(documentId);
    assertThat(approved.documentId()).isEqualTo(documentId);
    assertThat(approved.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_APPROVAL);
  }

  private UploadAccepted uploadDocument(byte[] pdfBytes) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.APPLICATION_PDF);
    fileHeaders.setContentDispositionFormData("file", "artisanal_ice_cube_march_2024.pdf");
    HttpEntity<ByteArrayResource> filePart =
        new HttpEntity<>(
            new ByteArrayResource(pdfBytes) {
              @Override
              public String getFilename() {
                return "artisanal_ice_cube_march_2024.pdf";
              }
            },
            fileHeaders);

    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("file", filePart);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(form, headers);

    ResponseEntity<UploadAccepted> response =
        restTemplate.exchange(
            url("/api/organizations/{orgId}/documents"),
            HttpMethod.POST,
            request,
            UploadAccepted.class,
            ORG_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UploadAccepted body = response.getBody();
    assertThat(body).isNotNull();
    return body;
  }

  private UUID awaitDocumentReachesAwaitingReview() {
    return await()
        .atMost(Duration.ofMinutes(2))
        .pollInterval(Duration.ofSeconds(2))
        .until(this::findAwaitingReview, v -> v != null);
  }

  private UUID findAwaitingReview() {
    DashboardResponse dashboard = fetchDashboard();
    if (dashboard == null) {
      return null;
    }
    List<DocumentView> docs = dashboard.documents();
    if (docs == null) {
      return null;
    }
    return docs.stream()
        .filter(d -> d.currentStatus() == WorkflowStatus.AWAITING_REVIEW)
        .map(DocumentView::documentId)
        .findFirst()
        .orElse(null);
  }

  private DashboardResponse fetchDashboard() {
    ResponseEntity<DashboardResponse> response =
        restTemplate.exchange(
            url("/api/organizations/{orgId}/documents"),
            HttpMethod.GET,
            HttpEntity.EMPTY,
            new ParameterizedTypeReference<DashboardResponse>() {},
            ORG_ID);
    if (response.getStatusCode() != HttpStatus.OK) {
      return null;
    }
    return response.getBody();
  }

  private DocumentView approveDocument(UUID documentId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> request = new HttpEntity<>("{\"action\":\"Approve\"}", headers);

    ResponseEntity<DocumentView> response =
        restTemplate.exchange(
            url("/api/documents/{documentId}/actions"),
            HttpMethod.POST,
            request,
            DocumentView.class,
            documentId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    DocumentView body = response.getBody();
    assertThat(body).isNotNull();
    return body;
  }

  private byte[] loadSampleInvoiceBytes() throws IOException {
    String[] candidates = {
      "problem-statement/samples/riverside-bistro/invoices/artisanal_ice_cube_march_2024.pdf",
      "../problem-statement/samples/riverside-bistro/invoices/artisanal_ice_cube_march_2024.pdf"
    };
    for (String candidate : candidates) {
      Path p = Paths.get(candidate);
      if (Files.exists(p)) {
        return Files.readAllBytes(p);
      }
    }
    return Files.readAllBytes(Paths.get("src/test/resources/fixtures/sample-invoice.pdf"));
  }
}
