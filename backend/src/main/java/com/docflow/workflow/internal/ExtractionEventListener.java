package com.docflow.workflow.internal;

import com.docflow.c3.events.ExtractionCompleted;
import com.docflow.c3.events.ExtractionFailed;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.document.ReextractionStatus;
import com.docflow.platform.DocumentEventBus;
import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceReader;
import com.docflow.workflow.WorkflowStatus;
import com.docflow.workflow.events.DocumentStateChanged;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ExtractionEventListener {

  private static final Logger LOG = LoggerFactory.getLogger(ExtractionEventListener.class);

  private static final String CLEAR_FLAG_KEEP_STAGE_SQL =
      "UPDATE workflow_instances SET "
          + "current_status = :currentStatus, "
          + "workflow_origin_stage = NULL, "
          + "flag_comment = NULL, "
          + "updated_at = :newUpdatedAt "
          + "WHERE document_id = :documentId";

  private final DocumentReader documentReader;
  private final DocumentWriter documentWriter;
  private final WorkflowInstanceReader workflowInstanceReader;
  private final NamedParameterJdbcOperations jdbc;
  private final DocumentEventBus eventBus;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public ExtractionEventListener(
      DocumentReader documentReader,
      DocumentWriter documentWriter,
      WorkflowInstanceReader workflowInstanceReader,
      NamedParameterJdbcOperations jdbc,
      DocumentEventBus eventBus,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.documentReader = documentReader;
    this.documentWriter = documentWriter;
    this.workflowInstanceReader = workflowInstanceReader;
    this.jdbc = jdbc;
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
      if (document.reextractionStatus() != ReextractionStatus.IN_PROGRESS) {
        LOG.info(
            "ExtractionCompleted ignored — documentId={} reextractionStatus={} (expected IN_PROGRESS)",
            documentId,
            document.reextractionStatus());
        return;
      }

      WorkflowInstance instance = workflowInstanceReader.getByDocumentId(documentId).orElse(null);
      if (instance == null) {
        LOG.warn(
            "ExtractionCompleted ignored — no workflow instance for documentId={}", documentId);
        return;
      }

      String newDocTypeId = event.detectedDocumentType();

      transactionTemplate.executeWithoutResult(
          status -> {
            documentWriter.updateExtraction(documentId, newDocTypeId, event.extractedFields());
            documentWriter.setReextractionStatus(documentId, ReextractionStatus.NONE);
            jdbc.update(
                CLEAR_FLAG_KEEP_STAGE_SQL,
                new MapSqlParameterSource()
                    .addValue("documentId", documentId)
                    .addValue("currentStatus", WorkflowStatus.AWAITING_REVIEW.name())
                    .addValue("newUpdatedAt", Timestamp.from(Instant.now(clock))));
          });

      eventBus.publish(
          new DocumentStateChanged(
              documentId,
              document.storedDocumentId(),
              document.organizationId(),
              instance.currentStageId(),
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
