package com.docflow.c3.pipeline;

import com.docflow.ingestion.StoredDocumentId;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ProcessingDocumentService {

  private final ProcessingPipelineOrchestrator orchestrator;

  ProcessingDocumentService(ProcessingPipelineOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  @Async
  public void start(StoredDocumentId storedDocumentId, ProcessingDocumentId processingDocumentId) {
    orchestrator.run(storedDocumentId, processingDocumentId);
  }
}
