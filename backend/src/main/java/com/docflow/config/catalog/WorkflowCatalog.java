package com.docflow.config.catalog;

import java.util.Optional;

public interface WorkflowCatalog {

  Optional<WorkflowView> getWorkflow(String orgId, String docTypeId);
}
