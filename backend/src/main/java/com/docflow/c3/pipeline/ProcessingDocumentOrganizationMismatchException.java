package com.docflow.c3.pipeline;

public class ProcessingDocumentOrganizationMismatchException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ProcessingDocumentId processingDocumentId;
  private final String processingOrganizationId;
  private final String storedOrganizationId;

  public ProcessingDocumentOrganizationMismatchException(
      ProcessingDocumentId processingDocumentId,
      String processingOrganizationId,
      String storedOrganizationId) {
    super(
        "ProcessingDocument "
            + processingDocumentId
            + " organization_id ("
            + processingOrganizationId
            + ") does not match parent StoredDocument ("
            + storedOrganizationId
            + ")");
    this.processingDocumentId = processingDocumentId;
    this.processingOrganizationId = processingOrganizationId;
    this.storedOrganizationId = storedOrganizationId;
  }

  public ProcessingDocumentId processingDocumentId() {
    return processingDocumentId;
  }

  public String processingOrganizationId() {
    return processingOrganizationId;
  }

  public String storedOrganizationId() {
    return storedOrganizationId;
  }
}
