package com.docflow.workflow.internal;

import com.docflow.c3.llm.LlmExtractor;
import com.docflow.c3.llm.RetypeAlreadyInProgressException;
import com.docflow.workflow.events.RetypeRequested;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RetypeRequestedListener {

  private static final Logger LOG = LoggerFactory.getLogger(RetypeRequestedListener.class);

  private final LlmExtractor llmExtractor;

  public RetypeRequestedListener(LlmExtractor llmExtractor) {
    this.llmExtractor = llmExtractor;
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRetypeRequested(RetypeRequested event) {
    UUID documentId = event.documentId();
    try {
      llmExtractor.extract(documentId, event.newDocTypeId());
    } catch (RetypeAlreadyInProgressException e) {
      LOG.info(
          "RetypeRequested ignored — concurrent retype already running for documentId={}",
          documentId);
    } catch (RuntimeException e) {
      LOG.warn("RetypeRequested listener failed for documentId={}", documentId, e);
    }
  }
}
