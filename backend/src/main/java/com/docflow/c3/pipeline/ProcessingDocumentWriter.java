package com.docflow.c3.pipeline;

public interface ProcessingDocumentWriter {

  void updateStep(ProcessingDocumentId id, String currentStep);

  void updateRawText(ProcessingDocumentId id, String rawText);

  void markFailed(ProcessingDocumentId id, String lastError);
}
