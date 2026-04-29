package com.docflow.workflow;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowInstanceReader {

  Optional<WorkflowInstance> getByDocumentId(UUID documentId);
}
