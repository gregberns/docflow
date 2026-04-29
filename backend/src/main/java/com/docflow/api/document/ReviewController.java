package com.docflow.api.document;

import com.docflow.api.dto.DocumentView;
import com.docflow.api.dto.RetypeAccepted;
import com.docflow.api.dto.RetypeRequest;
import com.docflow.api.dto.ReviewFieldsPatch;
import com.docflow.api.error.DocflowException.FieldError;
import com.docflow.api.error.InvalidActionException;
import com.docflow.api.error.ReextractionInProgressException;
import com.docflow.api.error.UnknownDocTypeException;
import com.docflow.api.error.UnknownDocumentException;
import com.docflow.api.error.ValidationException;
import com.docflow.config.catalog.DocumentTypeCatalog;
import com.docflow.config.catalog.DocumentTypeSchemaView;
import com.docflow.config.catalog.FieldView;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import com.docflow.workflow.WorkflowAction;
import com.docflow.workflow.WorkflowEngine;
import com.docflow.workflow.WorkflowError;
import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceReader;
import com.docflow.workflow.WorkflowOutcome;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class ReviewController {

  private final WorkflowEngine workflowEngine;
  private final DocumentReader documentReader;
  private final DocumentWriter documentWriter;
  private final StoredDocumentReader storedDocumentReader;
  private final WorkflowInstanceReader workflowInstanceReader;
  private final WorkflowCatalog workflowCatalog;
  private final DocumentTypeCatalog documentTypeCatalog;
  private final OrganizationCatalog organizationCatalog;

  public ReviewController(
      WorkflowEngine workflowEngine,
      DocumentReader documentReader,
      DocumentWriter documentWriter,
      StoredDocumentReader storedDocumentReader,
      WorkflowInstanceReader workflowInstanceReader,
      WorkflowCatalog workflowCatalog,
      DocumentTypeCatalog documentTypeCatalog,
      OrganizationCatalog organizationCatalog) {
    this.workflowEngine = workflowEngine;
    this.documentReader = documentReader;
    this.documentWriter = documentWriter;
    this.storedDocumentReader = storedDocumentReader;
    this.workflowInstanceReader = workflowInstanceReader;
    this.workflowCatalog = workflowCatalog;
    this.documentTypeCatalog = documentTypeCatalog;
    this.organizationCatalog = organizationCatalog;
  }

  @PatchMapping("/{documentId}/review/fields")
  public DocumentView patchFields(
      @PathVariable UUID documentId, @Valid @RequestBody ReviewFieldsPatch patch) {
    Document document =
        documentReader
            .get(documentId)
            .orElseThrow(() -> new UnknownDocumentException(documentId.toString()));

    String orgId = document.organizationId();
    String docTypeId = document.detectedDocumentType();
    DocumentTypeSchemaView schema =
        documentTypeCatalog
            .getDocumentTypeSchema(orgId, docTypeId)
            .orElseThrow(() -> new UnknownDocTypeException(String.valueOf(docTypeId)));

    List<FieldError> violations = new ArrayList<>();
    validateFields(schema.fields(), patch.extractedFields(), "extractedFields", violations);
    if (!violations.isEmpty()) {
      throw new ValidationException("extractedFields validation failed", violations);
    }

    documentWriter.updateExtraction(documentId, docTypeId, patch.extractedFields());
    return loadDocumentView(documentId);
  }

  @PostMapping("/{documentId}/review/retype")
  public ResponseEntity<RetypeAccepted> retype(
      @PathVariable UUID documentId, @Valid @RequestBody RetypeRequest request) {
    Document document =
        documentReader
            .get(documentId)
            .orElseThrow(() -> new UnknownDocumentException(documentId.toString()));

    String orgId = document.organizationId();
    String newDocTypeId = request.newDocumentType();

    List<String> allowed = organizationCatalog.getAllowedDocTypes(orgId);
    if (!allowed.contains(newDocTypeId)) {
      throw new UnknownDocTypeException(newDocTypeId);
    }
    if (documentTypeCatalog.getDocumentTypeSchema(orgId, newDocTypeId).isEmpty()) {
      throw new UnknownDocTypeException(newDocTypeId);
    }

    WorkflowOutcome outcome =
        workflowEngine.applyAction(documentId, new WorkflowAction.Resolve(newDocTypeId));
    if (outcome instanceof WorkflowOutcome.Failure failure) {
      throw mapWorkflowError(failure.error());
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RetypeAccepted("IN_PROGRESS"));
  }

  private RuntimeException mapWorkflowError(WorkflowError error) {
    return switch (error) {
      case WorkflowError.UnknownDocument unknown ->
          new UnknownDocumentException(unknown.documentId().toString());
      case WorkflowError.InvalidAction invalid ->
          new InvalidActionException(
              "Action "
                  + invalid.actionType()
                  + " not allowed in stage "
                  + invalid.currentStageId());
      case WorkflowError.ValidationFailed validation ->
          new ValidationException(
              "Action validation failed",
              validation.details().stream()
                  .map(d -> new FieldError(d.path(), d.message()))
                  .toList());
      case WorkflowError.ExtractionInProgress inProgress ->
          new ReextractionInProgressException(inProgress.documentId().toString());
    };
  }

  private void validateFields(
      List<FieldView> schema,
      Map<String, Object> values,
      String pathPrefix,
      List<FieldError> errors) {
    for (FieldView field : schema) {
      String path = pathPrefix + "." + field.name();
      Object value = values == null ? null : values.get(field.name());
      if (value == null) {
        if (field.required()) {
          errors.add(new FieldError(path, "is required"));
        }
        continue;
      }
      validateField(field, value, path, errors);
    }
  }

  private void validateField(FieldView field, Object value, String path, List<FieldError> errors) {
    String type = field.type();
    if (type == null) {
      return;
    }
    switch (type) {
      case "STRING", "DATE" -> {
        if (!(value instanceof String)) {
          errors.add(new FieldError(path, "must be a string"));
        }
      }
      case "DECIMAL" -> {
        if (!(value instanceof Number) && !(value instanceof String)) {
          errors.add(new FieldError(path, "must be a number"));
        }
      }
      case "ENUM" -> {
        if (!(value instanceof String s)) {
          errors.add(new FieldError(path, "must be a string"));
        } else if (field.enumValues() != null && !field.enumValues().contains(s)) {
          errors.add(new FieldError(path, "must be one of " + field.enumValues()));
        }
      }
      case "ARRAY" -> {
        if (!(value instanceof List<?> list)) {
          errors.add(new FieldError(path, "must be an array"));
          return;
        }
        if (field.itemFields() == null) {
          return;
        }
        for (int i = 0; i < list.size(); i++) {
          Object item = list.get(i);
          if (!(item instanceof Map<?, ?> itemMap)) {
            errors.add(new FieldError(path + "[" + i + "]", "must be an object"));
            continue;
          }
          @SuppressWarnings("unchecked")
          Map<String, Object> typedItem = (Map<String, Object>) itemMap;
          validateFields(field.itemFields(), typedItem, path + "[" + i + "]", errors);
        }
      }
      default -> errors.add(new FieldError(path, "unknown field type: " + type));
    }
  }

  private DocumentView loadDocumentView(UUID documentId) {
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
