package com.docflow.c3.audit.internal;

import com.docflow.c3.audit.CallType;
import com.docflow.c3.audit.LlmCallAudit;
import com.docflow.c3.audit.LlmCallAuditId;
import com.docflow.c3.audit.LlmCallAuditReader;
import com.docflow.ingestion.StoredDocumentId;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class LlmCallAuditJpaReader implements LlmCallAuditReader {

  private final LlmCallAuditEntityRepository repository;

  LlmCallAuditJpaReader(LlmCallAuditEntityRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<LlmCallAudit> listForStoredDocument(StoredDocumentId id) {
    return repository.findByStoredDocumentIdOrderByAtDesc(id.value()).stream()
        .map(LlmCallAuditJpaReader::toRecord)
        .toList();
  }

  private static LlmCallAudit toRecord(LlmCallAuditEntity entity) {
    return new LlmCallAudit(
        LlmCallAuditId.of(entity.getId()),
        StoredDocumentId.of(entity.getStoredDocumentId()),
        entity.getProcessingDocumentId(),
        entity.getDocumentId(),
        entity.getOrganizationId(),
        CallType.fromDbValue(entity.getCallType()),
        entity.getModelId(),
        entity.getError(),
        entity.getAt());
  }
}
