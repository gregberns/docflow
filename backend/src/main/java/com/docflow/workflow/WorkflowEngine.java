package com.docflow.workflow;

import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.TransitionView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.ReextractionStatus;
import com.docflow.platform.DocumentEventBus;
import com.docflow.workflow.events.DocumentStateChanged;
import com.docflow.workflow.events.RetypeRequested;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorkflowEngine {

  private static final String STAGE_KIND_REVIEW = "review";

  private final WorkflowCatalog catalog;
  private final DocumentReader documentReader;
  private final WorkflowInstanceReader instanceReader;
  private final WorkflowInstanceWriter instanceWriter;
  private final DocumentEventBus eventBus;
  private final TransitionResolver transitionResolver;
  private final Clock clock;

  public WorkflowEngine(
      WorkflowCatalog catalog,
      DocumentReader documentReader,
      WorkflowInstanceReader instanceReader,
      WorkflowInstanceWriter instanceWriter,
      DocumentEventBus eventBus,
      Clock clock) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.documentReader = Objects.requireNonNull(documentReader, "documentReader");
    this.instanceReader = Objects.requireNonNull(instanceReader, "instanceReader");
    this.instanceWriter = Objects.requireNonNull(instanceWriter, "instanceWriter");
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.transitionResolver = new TransitionResolver(catalog);
  }

  @Transactional
  public WorkflowOutcome applyAction(UUID documentId, WorkflowAction action) {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(action, "action");

    if (action instanceof WorkflowAction.Flag flag) {
      String comment = flag.comment();
      if (comment == null || comment.trim().isEmpty()) {
        return new WorkflowOutcome.Failure(
            new WorkflowError.ValidationFailed(
                List.of(new WorkflowError.ValidationDetail("comment", "must be non-empty"))));
      }
    }

    Document document = documentReader.get(documentId).orElse(null);
    if (document == null) {
      return new WorkflowOutcome.Failure(new WorkflowError.UnknownDocument(documentId));
    }
    WorkflowInstance instance = instanceReader.getByDocumentId(documentId).orElse(null);
    if (instance == null) {
      return new WorkflowOutcome.Failure(new WorkflowError.UnknownDocument(documentId));
    }

    return switch (action) {
      case WorkflowAction.Approve approve -> handleApprove(document, instance, approve);
      case WorkflowAction.Reject reject -> handleReject(document, instance, reject);
      case WorkflowAction.Flag flag -> handleFlag(document, instance, flag);
      case WorkflowAction.Resolve resolve -> handleResolve(document, instance, resolve);
    };
  }

  private WorkflowOutcome handleApprove(
      Document document, WorkflowInstance instance, WorkflowAction.Approve action) {
    return advanceViaResolver(document, instance, action, "APPROVE", null);
  }

  private WorkflowOutcome handleReject(
      Document document, WorkflowInstance instance, WorkflowAction.Reject action) {
    return advanceViaResolver(document, instance, action, "REJECT", null);
  }

  private WorkflowOutcome handleFlag(
      Document document, WorkflowInstance instance, WorkflowAction.Flag action) {
    String orgId = document.organizationId();
    String docTypeId = document.detectedDocumentType();
    TransitionResolver.Result resolution =
        transitionResolver.resolve(
            orgId, docTypeId, instance.currentStageId(), action, document.extractedFields());
    if (resolution instanceof TransitionResolver.Result.Invalid invalid) {
      return new WorkflowOutcome.Failure(invalid.error());
    }
    String originStage = instance.currentStageId();
    try {
      instanceWriter.setFlag(
          document.id(), originStage, action.comment(), catalog, orgId, docTypeId);
    } catch (StaleWorkflowStateException stale) {
      return staleAsInvalidAction(stale, "FLAG");
    }
    WorkflowInstance updated = requireUpdatedInstance(document.id());
    publish(document, updated, "FLAG", action.comment());
    return new WorkflowOutcome.Success(updated);
  }

  private WorkflowOutcome handleResolve(
      Document document, WorkflowInstance instance, WorkflowAction.Resolve action) {
    String currentDocType = document.detectedDocumentType();
    String requestedDocType = action.newDocTypeId();
    boolean typeChange =
        requestedDocType != null
            && !requestedDocType.isBlank()
            && !requestedDocType.equals(currentDocType);

    if (instance.workflowOriginStage() == null
        && !(typeChange
            && isReviewStage(
                document.organizationId(), currentDocType, instance.currentStageId()))) {
      return new WorkflowOutcome.Failure(
          new WorkflowError.InvalidAction(instance.currentStageId(), "RESOLVE"));
    }

    if (typeChange) {
      return handleResolveWithTypeChange(document, requestedDocType);
    }

    String orgId = document.organizationId();
    String docTypeId = document.detectedDocumentType();
    try {
      instanceWriter.clearFlag(document.id(), catalog, orgId, docTypeId);
    } catch (StaleWorkflowStateException stale) {
      return staleAsInvalidAction(stale, "RESOLVE");
    }
    WorkflowInstance updated = requireUpdatedInstance(document.id());
    publish(document, updated, "RESOLVE", null);
    return new WorkflowOutcome.Success(updated);
  }

  private WorkflowOutcome handleResolveWithTypeChange(Document document, String newDocTypeId) {
    if (document.reextractionStatus() == ReextractionStatus.IN_PROGRESS) {
      return new WorkflowOutcome.Failure(new WorkflowError.ExtractionInProgress(document.id()));
    }

    WorkflowInstance instance = requireUpdatedInstance(document.id());
    Instant now = Instant.now(clock);
    eventBus.publish(
        new DocumentStateChanged(
            document.id(),
            document.storedDocumentId(),
            document.organizationId(),
            instance.currentStageId(),
            instance.currentStatus().name(),
            ReextractionStatus.IN_PROGRESS.name(),
            "RESOLVE",
            null,
            now));
    eventBus.publish(
        new RetypeRequested(document.id(), document.organizationId(), newDocTypeId, now));
    return new WorkflowOutcome.Success(instance);
  }

  private WorkflowOutcome advanceViaResolver(
      Document document,
      WorkflowInstance instance,
      WorkflowAction action,
      String actionName,
      String comment) {
    String orgId = document.organizationId();
    String docTypeId = document.detectedDocumentType();
    TransitionResolver.Result resolution =
        transitionResolver.resolve(
            orgId, docTypeId, instance.currentStageId(), action, document.extractedFields());
    if (resolution instanceof TransitionResolver.Result.Invalid invalid) {
      return new WorkflowOutcome.Failure(invalid.error());
    }
    TransitionView transition = ((TransitionResolver.Result.Match) resolution).transition();
    try {
      instanceWriter.advanceStage(document.id(), transition.toStage(), catalog, orgId, docTypeId);
    } catch (StaleWorkflowStateException stale) {
      return staleAsInvalidAction(stale, actionName);
    }
    WorkflowInstance updated = requireUpdatedInstance(document.id());
    publish(document, updated, actionName, comment);
    return new WorkflowOutcome.Success(updated);
  }

  private WorkflowOutcome staleAsInvalidAction(StaleWorkflowStateException stale, String action) {
    return new WorkflowOutcome.Failure(
        new WorkflowError.InvalidAction(stale.observedFromStageId(), action));
  }

  private WorkflowInstance requireUpdatedInstance(UUID documentId) {
    return instanceReader
        .getByDocumentId(documentId)
        .orElseThrow(
            () -> new IllegalStateException("workflow instance vanished for " + documentId));
  }

  private boolean isReviewStage(String orgId, String docTypeId, String stageId) {
    if (orgId == null || docTypeId == null || stageId == null) {
      return false;
    }
    WorkflowView workflow = catalog.getWorkflow(orgId, docTypeId).orElse(null);
    if (workflow == null) {
      return false;
    }
    for (StageView stage : workflow.stages()) {
      if (stage.id().equals(stageId)) {
        return STAGE_KIND_REVIEW.equals(stage.kind().toLowerCase(Locale.ROOT));
      }
    }
    return false;
  }

  private void publish(
      Document document, WorkflowInstance updated, String actionName, String comment) {
    eventBus.publish(
        new DocumentStateChanged(
            document.id(),
            document.storedDocumentId(),
            document.organizationId(),
            updated.currentStageId(),
            updated.currentStatus().name(),
            document.reextractionStatus().name(),
            actionName,
            comment,
            Instant.now(clock)));
  }
}
