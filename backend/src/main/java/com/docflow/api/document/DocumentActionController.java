package com.docflow.api.document;

import com.docflow.api.dto.ActionRequest;
import com.docflow.api.dto.DocumentView;
import com.docflow.api.error.DocflowException.FieldError;
import com.docflow.api.error.InvalidActionException;
import com.docflow.api.error.ReextractionInProgressException;
import com.docflow.api.error.UnknownDocumentException;
import com.docflow.api.error.ValidationException;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
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
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class DocumentActionController {

  private final WorkflowEngine workflowEngine;
  private final DocumentReader documentReader;
  private final StoredDocumentReader storedDocumentReader;
  private final WorkflowInstanceReader workflowInstanceReader;
  private final WorkflowCatalog workflowCatalog;

  public DocumentActionController(
      WorkflowEngine workflowEngine,
      DocumentReader documentReader,
      StoredDocumentReader storedDocumentReader,
      WorkflowInstanceReader workflowInstanceReader,
      WorkflowCatalog workflowCatalog) {
    this.workflowEngine = workflowEngine;
    this.documentReader = documentReader;
    this.storedDocumentReader = storedDocumentReader;
    this.workflowInstanceReader = workflowInstanceReader;
    this.workflowCatalog = workflowCatalog;
  }

  @PostMapping("/{documentId}/actions")
  public DocumentView act(
      @PathVariable UUID documentId, @Valid @RequestBody ActionRequest request) {
    WorkflowAction action = toDomainAction(request);
    WorkflowOutcome outcome = workflowEngine.applyAction(documentId, action);
    if (outcome instanceof WorkflowOutcome.Failure failure) {
      throw mapWorkflowError(failure.error());
    }
    return loadDocumentView(documentId);
  }

  private WorkflowAction toDomainAction(ActionRequest request) {
    return switch (request) {
      case ActionRequest.Approve a -> {
        java.util.Objects.requireNonNull(a);
        yield new WorkflowAction.Approve();
      }
      case ActionRequest.Reject r -> {
        java.util.Objects.requireNonNull(r);
        yield new WorkflowAction.Reject();
      }
      case ActionRequest.Flag f -> new WorkflowAction.Flag(f.comment());
      case ActionRequest.Resolve r -> {
        java.util.Objects.requireNonNull(r);
        yield new WorkflowAction.Resolve(null);
      }
    };
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
          new ValidationException("Action validation failed", toFieldErrors(validation.details()));
      case WorkflowError.ExtractionInProgress inProgress ->
          new ReextractionInProgressException(inProgress.documentId().toString());
    };
  }

  private List<FieldError> toFieldErrors(List<WorkflowError.ValidationDetail> details) {
    return details.stream().map(d -> new FieldError(d.path(), d.message())).toList();
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
