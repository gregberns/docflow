package com.docflow.workflow;

import com.docflow.config.catalog.WorkflowCatalog;
import java.util.UUID;

public interface WorkflowInstanceWriter {

  void insert(WorkflowInstance instance, String documentTypeId);

  void advanceStage(
      UUID documentId, String newStageId, WorkflowCatalog catalog, String orgId, String docTypeId);

  void setFlag(
      UUID documentId,
      String originStageId,
      String comment,
      WorkflowCatalog catalog,
      String orgId,
      String docTypeId);

  void clearFlag(UUID documentId, WorkflowCatalog catalog, String orgId, String docTypeId);

  void clearOriginKeepStage(UUID documentId, WorkflowStatus newStatus);
}
