package com.docflow.c3.audit.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface LlmCallAuditEntityRepository extends Repository<LlmCallAuditEntity, UUID> {

  LlmCallAuditEntity save(LlmCallAuditEntity entity);

  List<LlmCallAuditEntity> findByStoredDocumentIdOrderByAtDesc(UUID storedDocumentId);
}
