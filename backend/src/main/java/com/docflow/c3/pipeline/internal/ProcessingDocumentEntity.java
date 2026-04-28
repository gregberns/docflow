package com.docflow.c3.pipeline.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processing_documents")
class ProcessingDocumentEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "stored_document_id", nullable = false)
  private UUID storedDocumentId;

  @Column(name = "organization_id", nullable = false)
  private String organizationId;

  @Column(name = "current_step", nullable = false)
  private String currentStep;

  @Column(name = "raw_text")
  private String rawText;

  @Column(name = "last_error")
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ProcessingDocumentEntity() {}

  ProcessingDocumentEntity(
      UUID id,
      UUID storedDocumentId,
      String organizationId,
      String currentStep,
      String rawText,
      String lastError,
      Instant createdAt) {
    this.id = id;
    this.storedDocumentId = storedDocumentId;
    this.organizationId = organizationId;
    this.currentStep = currentStep;
    this.rawText = rawText;
    this.lastError = lastError;
    this.createdAt = createdAt;
  }

  UUID getId() {
    return id;
  }

  UUID getStoredDocumentId() {
    return storedDocumentId;
  }

  String getOrganizationId() {
    return organizationId;
  }

  String getCurrentStep() {
    return currentStep;
  }

  void setCurrentStep(String currentStep) {
    this.currentStep = currentStep;
  }

  String getRawText() {
    return rawText;
  }

  void setRawText(String rawText) {
    this.rawText = rawText;
  }

  String getLastError() {
    return lastError;
  }

  void setLastError(String lastError) {
    this.lastError = lastError;
  }

  Instant getCreatedAt() {
    return createdAt;
  }
}
