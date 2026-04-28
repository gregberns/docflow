package com.docflow.c3.audit;

import com.docflow.ingestion.StoredDocumentId;
import java.util.List;

public interface LlmCallAuditReader {

  List<LlmCallAudit> listForStoredDocument(StoredDocumentId id);
}
