package com.docflow.c3.pipeline;

public class ProcessingDocumentNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ProcessingDocumentId processingDocumentId;

  public ProcessingDocumentNotFoundException(ProcessingDocumentId processingDocumentId) {
    super("ProcessingDocument not found: " + processingDocumentId);
    this.processingDocumentId = processingDocumentId;
  }

  public ProcessingDocumentId processingDocumentId() {
    return processingDocumentId;
  }
}
