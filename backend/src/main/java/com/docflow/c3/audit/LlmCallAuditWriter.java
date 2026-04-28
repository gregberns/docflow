package com.docflow.c3.audit;

public interface LlmCallAuditWriter {

  void insert(LlmCallAudit audit);
}
