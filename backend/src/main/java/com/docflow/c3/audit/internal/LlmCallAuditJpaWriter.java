package com.docflow.c3.audit.internal;

import com.docflow.c3.audit.LlmCallAudit;
import com.docflow.c3.audit.LlmCallAuditWriter;
import org.springframework.stereotype.Component;

@Component
class LlmCallAuditJpaWriter implements LlmCallAuditWriter {

  private final LlmCallAuditEntityRepository repository;

  LlmCallAuditJpaWriter(LlmCallAuditEntityRepository repository) {
    this.repository = repository;
  }

  @Override
  public void insert(LlmCallAudit audit) {
    boolean hasProcessing = audit.processingDocumentId() != null;
    boolean hasDocument = audit.documentId() != null;
    if (hasProcessing == hasDocument) {
      throw new IllegalArgumentException(
          "exactly one of processingDocumentId or documentId must be set");
    }
    repository.save(LlmCallAuditEntity.from(audit));
  }
}
