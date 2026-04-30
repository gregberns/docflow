package com.docflow.api.dashboard;

import com.docflow.api.dto.DashboardStats;
import com.docflow.api.dto.DocumentView;
import com.docflow.api.dto.ProcessingItem;
import com.docflow.workflow.WorkflowStatus;
import java.util.List;
import java.util.Optional;

public interface DashboardRepository {

  List<ProcessingItem> listProcessing(String orgId);

  List<DocumentView> listDocuments(
      String orgId,
      Optional<WorkflowStatus> statusFilter,
      Optional<String> stageDisplayNameFilter,
      Optional<String> docTypeFilter);

  DashboardStats stats(String orgId);
}
