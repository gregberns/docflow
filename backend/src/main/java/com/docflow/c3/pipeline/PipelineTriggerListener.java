package com.docflow.c3.pipeline;

import com.docflow.c3.events.StoredDocumentIngested;
import com.docflow.ingestion.StoredDocumentId;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class PipelineTriggerListener {

  private final ProcessingDocumentService service;

  PipelineTriggerListener(ProcessingDocumentService service) {
    this.service = service;
  }

  @EventListener
  public void onIngested(StoredDocumentIngested event) {
    service.start(
        StoredDocumentId.of(event.storedDocumentId()),
        ProcessingDocumentId.of(event.processingDocumentId()));
  }
}
