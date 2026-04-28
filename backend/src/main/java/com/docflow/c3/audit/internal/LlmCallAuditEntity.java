package com.docflow.c3.audit.internal;

import com.docflow.c3.audit.LlmCallAudit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_call_audit")
class LlmCallAuditEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "stored_document_id", nullable = false)
  private UUID storedDocumentId;

  @Column(name = "processing_document_id")
  private UUID processingDocumentId;

  @Column(name = "document_id")
  private UUID documentId;

  @Column(name = "organization_id", nullable = false)
  private String organizationId;

  @Column(name = "call_type", nullable = false)
  private String callType;

  @Column(name = "model_id", nullable = false)
  private String modelId;

  @Column(name = "error")
  private String error;

  @Column(name = "at", nullable = false)
  private Instant at;

  protected LlmCallAuditEntity() {}

  static LlmCallAuditEntity from(LlmCallAudit audit) {
    LlmCallAuditEntity entity = new LlmCallAuditEntity();
    entity.id = audit.id().value();
    entity.storedDocumentId = audit.storedDocumentId().value();
    entity.processingDocumentId = audit.processingDocumentId();
    entity.documentId = audit.documentId();
    entity.organizationId = audit.organizationId();
    entity.callType = audit.callType().dbValue();
    entity.modelId = audit.modelId();
    entity.error = audit.error();
    entity.at = audit.at();
    return entity;
  }

  UUID getId() {
    return id;
  }

  UUID getStoredDocumentId() {
    return storedDocumentId;
  }

  UUID getProcessingDocumentId() {
    return processingDocumentId;
  }

  UUID getDocumentId() {
    return documentId;
  }

  String getOrganizationId() {
    return organizationId;
  }

  String getCallType() {
    return callType;
  }

  String getModelId() {
    return modelId;
  }

  String getError() {
    return error;
  }

  Instant getAt() {
    return at;
  }
}
