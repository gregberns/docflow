package com.docflow.api.document;

import com.docflow.api.dto.DocumentView;
import com.docflow.api.error.UnknownDocumentException;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import com.docflow.ingestion.storage.StoredDocumentStorage;
import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceReader;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

  private final DocumentReader documentReader;
  private final StoredDocumentReader storedDocumentReader;
  private final WorkflowInstanceReader workflowInstanceReader;
  private final WorkflowCatalog workflowCatalog;
  private final StoredDocumentStorage storedDocumentStorage;

  public DocumentController(
      DocumentReader documentReader,
      StoredDocumentReader storedDocumentReader,
      WorkflowInstanceReader workflowInstanceReader,
      WorkflowCatalog workflowCatalog,
      StoredDocumentStorage storedDocumentStorage) {
    this.documentReader = documentReader;
    this.storedDocumentReader = storedDocumentReader;
    this.workflowInstanceReader = workflowInstanceReader;
    this.workflowCatalog = workflowCatalog;
    this.storedDocumentStorage = storedDocumentStorage;
  }

  @GetMapping("/{documentId}")
  public DocumentView get(@PathVariable UUID documentId) {
    Document document =
        documentReader
            .get(documentId)
            .orElseThrow(() -> new UnknownDocumentException(documentId.toString()));

    StoredDocumentId storedId = StoredDocumentId.of(document.storedDocumentId());
    StoredDocument stored =
        storedDocumentReader
            .get(storedId)
            .orElseThrow(() -> new UnknownDocumentException(documentId.toString()));

    WorkflowInstance instance =
        workflowInstanceReader
            .getByDocumentId(documentId)
            .orElseThrow(() -> new UnknownDocumentException(documentId.toString()));

    String stageDisplayName =
        resolveStageDisplayName(
            document.organizationId(), document.detectedDocumentType(), instance.currentStageId());

    return new DocumentView(
        document.id(),
        document.organizationId(),
        stored.sourceFilename(),
        stored.mimeType(),
        stored.uploadedAt(),
        document.processedAt(),
        document.rawText(),
        instance.currentStageId(),
        stageDisplayName,
        instance.currentStatus(),
        instance.workflowOriginStage(),
        instance.flagComment(),
        document.detectedDocumentType(),
        document.extractedFields(),
        document.reextractionStatus());
  }

  @GetMapping("/{documentId}/file")
  public ResponseEntity<InputStreamResource> file(@PathVariable UUID documentId) {
    Document document =
        documentReader
            .get(documentId)
            .orElseThrow(() -> new UnknownDocumentException(documentId.toString()));

    StoredDocumentId storedId = StoredDocumentId.of(document.storedDocumentId());
    StoredDocument stored =
        storedDocumentReader
            .get(storedId)
            .orElseThrow(() -> new UnknownDocumentException(documentId.toString()));

    long contentLength = storedDocumentStorage.size(storedId);
    InputStream stream = storedDocumentStorage.openStream(storedId);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(stored.mimeType()));
    headers.setContentLength(contentLength);
    return ResponseEntity.ok().headers(headers).body(new InputStreamResource(stream));
  }

  private String resolveStageDisplayName(String orgId, String docTypeId, String stageId) {
    if (orgId == null || docTypeId == null || stageId == null) {
      return null;
    }
    return workflowCatalog
        .getWorkflow(orgId, docTypeId)
        .map(WorkflowView::stages)
        .flatMap(stages -> stages.stream().filter(s -> stageId.equals(s.id())).findFirst())
        .map(StageView::displayName)
        .orElse(null);
  }
}
