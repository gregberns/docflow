package com.docflow.workflow.internal;

import com.docflow.c3.events.ProcessingCompleted;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.document.Document;
import com.docflow.document.DocumentWriter;
import com.docflow.document.ReextractionStatus;
import com.docflow.platform.DocumentEventBus;
import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceWriter;
import com.docflow.workflow.WorkflowStatus;
import com.docflow.workflow.events.DocumentStateChanged;
import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ProcessingCompletedListener {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessingCompletedListener.class);
  private static final String STAGE_KIND_REVIEW = "review";

  private final WorkflowCatalog catalog;
  private final DocumentWriter documentWriter;
  private final WorkflowInstanceWriter workflowInstanceWriter;
  private final DocumentEventBus eventBus;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public ProcessingCompletedListener(
      WorkflowCatalog catalog,
      DocumentWriter documentWriter,
      WorkflowInstanceWriter workflowInstanceWriter,
      DocumentEventBus eventBus,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.catalog = catalog;
    this.documentWriter = documentWriter;
    this.workflowInstanceWriter = workflowInstanceWriter;
    this.eventBus = eventBus;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Async
  @EventListener
  public void onProcessingCompleted(ProcessingCompleted event) {
    String orgId = event.organizationId();
    String docTypeId = event.detectedDocumentType();
    StageView reviewStage = findReviewStage(orgId, docTypeId);

    UUID documentId = UuidCreator.getTimeOrderedEpoch();
    UUID workflowInstanceId = UuidCreator.getTimeOrderedEpoch();
    Instant now = Instant.now(clock);

    Document document =
        new Document(
            documentId,
            event.storedDocumentId(),
            orgId,
            docTypeId,
            event.extractedFields(),
            event.rawText(),
            now,
            ReextractionStatus.NONE);

    WorkflowInstance instance =
        new WorkflowInstance(
            workflowInstanceId,
            documentId,
            orgId,
            reviewStage.id(),
            WorkflowStatus.AWAITING_REVIEW,
            null,
            null,
            now);

    DocumentStateChanged stateChanged =
        new DocumentStateChanged(
            documentId,
            event.storedDocumentId(),
            orgId,
            reviewStage.id(),
            WorkflowStatus.AWAITING_REVIEW.name(),
            ReextractionStatus.NONE.name(),
            null,
            null,
            now);

    try {
      transactionTemplate.executeWithoutResult(
          status -> {
            documentWriter.insert(document);
            workflowInstanceWriter.insert(instance, docTypeId);
          });
    } catch (DuplicateKeyException e) {
      LOG.warn(
          "ProcessingCompleted ignored — duplicate storedDocumentId={} (org={}, docType={})",
          event.storedDocumentId(),
          orgId,
          docTypeId);
      return;
    }

    eventBus.publish(stateChanged);
  }

  private StageView findReviewStage(String orgId, String docTypeId) {
    WorkflowView workflow =
        catalog
            .getWorkflow(orgId, docTypeId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no workflow for org=" + orgId + " docType=" + docTypeId));
    for (StageView stage : workflow.stages()) {
      if (STAGE_KIND_REVIEW.equals(stage.kind().toLowerCase(Locale.ROOT))) {
        return stage;
      }
    }
    throw new IllegalStateException(
        "no review stage in workflow for org=" + orgId + " docType=" + docTypeId);
  }
}
