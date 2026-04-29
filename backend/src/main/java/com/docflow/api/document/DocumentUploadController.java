package com.docflow.api.document;

import com.docflow.api.dto.UploadAccepted;
import com.docflow.api.error.InvalidFileException;
import com.docflow.api.error.UnknownOrganizationException;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.ingestion.IngestionResult;
import com.docflow.ingestion.StoredDocumentIngestionService;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/organizations/{orgId}/documents")
public class DocumentUploadController {

  private final OrganizationCatalog organizationCatalog;
  private final StoredDocumentIngestionService ingestionService;

  public DocumentUploadController(
      OrganizationCatalog organizationCatalog, StoredDocumentIngestionService ingestionService) {
    this.organizationCatalog = organizationCatalog;
    this.ingestionService = ingestionService;
  }

  @PostMapping
  public ResponseEntity<UploadAccepted> upload(
      @PathVariable String orgId, @RequestParam("file") MultipartFile file) {
    if (organizationCatalog.getOrganization(orgId).isEmpty()) {
      throw new UnknownOrganizationException(orgId);
    }

    if (file == null || file.isEmpty()) {
      throw new InvalidFileException("Uploaded file is empty");
    }

    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new InvalidFileException("Could not read uploaded bytes: " + e.getMessage());
    }

    IngestionResult result =
        ingestionService.upload(orgId, file.getOriginalFilename(), file.getContentType(), bytes);

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new UploadAccepted(result.storedDocumentId(), result.processingDocumentId()));
  }
}
