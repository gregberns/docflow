package com.docflow.api.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.docflow.api.error.GlobalExceptionHandler;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.config.catalog.OrganizationView;
import com.docflow.ingestion.IngestionResult;
import com.docflow.ingestion.StoredDocumentIngestionService;
import com.docflow.ingestion.UnsupportedMediaTypeException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class DocumentUploadControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String ORG_ID = "org-alpha";

  private OrganizationCatalog organizationCatalog;
  private StoredDocumentIngestionService ingestionService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    organizationCatalog = mock(OrganizationCatalog.class);
    ingestionService = mock(StoredDocumentIngestionService.class);
    DocumentUploadController controller =
        new DocumentUploadController(organizationCatalog, ingestionService);
    mockMvc = standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test
  void validUpload_returns201WithIds() throws Exception {
    when(organizationCatalog.getOrganization(ORG_ID))
        .thenReturn(Optional.of(new OrganizationView(ORG_ID, "Org Alpha", "icon", List.of())));
    UUID storedId = UUID.randomUUID();
    UUID processingId = UUID.randomUUID();
    when(ingestionService.upload(eq(ORG_ID), any(), any(), any()))
        .thenReturn(new IngestionResult(storedId, processingId));

    MockMultipartFile file =
        new MockMultipartFile(
            "file", "invoice-001.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[] {1, 2, 3, 4});

    mockMvc
        .perform(multipart("/api/organizations/" + ORG_ID + "/documents").file(file))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.storedDocumentId").value(storedId.toString()))
        .andExpect(jsonPath("$.processingDocumentId").value(processingId.toString()));
  }

  @Test
  void unknownOrg_returns404Problem() throws Exception {
    when(organizationCatalog.getOrganization("missing")).thenReturn(Optional.empty());

    MockMultipartFile file =
        new MockMultipartFile(
            "file", "x.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[] {1, 2, 3});

    mockMvc
        .perform(multipart("/api/organizations/missing/documents").file(file))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_ORGANIZATION"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void emptyFile_returns400Problem() throws Exception {
    when(organizationCatalog.getOrganization(ORG_ID))
        .thenReturn(Optional.of(new OrganizationView(ORG_ID, "Org Alpha", "icon", List.of())));

    MockMultipartFile file =
        new MockMultipartFile("file", "empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]);

    mockMvc
        .perform(multipart("/api/organizations/" + ORG_ID + "/documents").file(file))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_FILE"))
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void unsupportedMediaType_returns415Problem() throws Exception {
    when(organizationCatalog.getOrganization(ORG_ID))
        .thenReturn(Optional.of(new OrganizationView(ORG_ID, "Org Alpha", "icon", List.of())));
    when(ingestionService.upload(eq(ORG_ID), any(), any(), any()))
        .thenThrow(new UnsupportedMediaTypeException("application/zip"));

    MockMultipartFile file =
        new MockMultipartFile("file", "bundle.zip", "application/zip", new byte[] {1, 2, 3, 4});

    mockMvc
        .perform(multipart("/api/organizations/" + ORG_ID + "/documents").file(file))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(content().contentType(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
        .andExpect(jsonPath("$.status").value(415));
  }
}
