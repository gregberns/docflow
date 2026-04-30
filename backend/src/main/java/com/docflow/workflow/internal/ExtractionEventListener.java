package com.docflow.workflow.internal;

import com.docflow.c3.events.ExtractionCompleted;
import com.docflow.c3.events.ExtractionFailed;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.document.ReextractionStatus;
import com.docflow.platform.DocumentEventBus;
import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceReader;
import com.docflow.workflow.WorkflowInstanceWriter;
import com.docflow.workflow.WorkflowStatus;
import com.docflow.workflow.events.DocumentStateChanged;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ExtractionEventListener {

  private static final Logger LOG = LoggerFactory.getLogger(ExtractionEventListener.class);

  private final DocumentReader documentReader;
  private final DocumentWriter documentWriter;
  private final WorkflowInstanceReader workflowInstanceReader;
  private final WorkflowInstanceWriter workflowInstanceWriter;
  private final WorkflowCatalog workflowCatalog;
  private final DocumentEventBus eventBus;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public ExtractionEventListener(
      DocumentReader documentReader,
      DocumentWriter documentWriter,
      WorkflowInstanceReader workflowInstanceReader,
      WorkflowInstanceWriter workflowInstanceWriter,
      WorkflowCatalog workflowCatalog,
      DocumentEventBus eventBus,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.documentReader = documentReader;
    this.documentWriter = documentWriter;
    this.workflowInstanceReader = workflowInstanceReader;
    this.workflowInstanceWriter = workflowInstanceWriter;
    this.workflowCatalog = workflowCatalog;
    this.eventBus = eventBus;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Async
  @EventListener
  public void onExtractionCompleted(ExtractionCompleted event) {
    UUID documentId = event.documentId();
    try {
      Document document = documentReader.get(documentId).orElse(null);
      if (document == null) {
        LOG.warn("ExtractionCompleted ignored — unknown documentId={}", documentId);
        return;
      }

      WorkflowInstance instance = workflowInstanceReader.getByDocumentId(documentId).orElse(null);
      if (instance == null) {
        LOG.warn(
            "ExtractionCompleted ignored — no workflow instance for documentId={}", documentId);
        return;
      }

      String newDocTypeId = event.detectedDocumentType();

      String orgId = document.organizationId();
      transactionTemplate.executeWithoutResult(
          status -> {
            documentWriter.updateExtraction(documentId, newDocTypeId, event.extractedFields());
            documentWriter.setReextractionStatus(documentId, ReextractionStatus.NONE);
            workflowInstanceWriter.clearOriginKeepStage(
                documentId, workflowCatalog, orgId, newDocTypeId);
          });

      WorkflowInstance updated =
          workflowInstanceReader.getByDocumentId(documentId).orElse(instance);
      eventBus.publish(
          new DocumentStateChanged(
              documentId,
              document.storedDocumentId(),
              document.organizationId(),
              updated.currentStageId(),
              WorkflowStatus.AWAITING_REVIEW.name(),
              ReextractionStatus.NONE.name(),
              null,
              null,
              Instant.now(clock)));
    } catch (RuntimeException e) {
      LOG.warn("ExtractionCompleted listener failed for documentId={}", documentId, e);
    }
  }

  @Async
  @EventListener
  public void onExtractionFailed(ExtractionFailed event) {
    UUID documentId = event.documentId();
    try {
      Document document = documentReader.get(documentId).orElse(null);
      if (document == null) {
        LOG.warn("ExtractionFailed ignored — unknown documentId={}", documentId);
        return;
      }

      WorkflowInstance instance = workflowInstanceReader.getByDocumentId(documentId).orElse(null);
      if (instance == null) {
        LOG.warn("ExtractionFailed ignored — no workflow instance for documentId={}", documentId);
        return;
      }

      eventBus.publish(
          new DocumentStateChanged(
              documentId,
              document.storedDocumentId(),
              document.organizationId(),
              instance.currentStageId(),
              instance.currentStatus().name(),
              ReextractionStatus.FAILED.name(),
              null,
              null,
              Instant.now(clock)));
    } catch (RuntimeException e) {
      LOG.warn("ExtractionFailed listener failed for documentId={}", documentId, e);
    }
  }
}
