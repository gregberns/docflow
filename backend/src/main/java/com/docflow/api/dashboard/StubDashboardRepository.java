package com.docflow.api.dashboard;

import com.docflow.api.dto.DashboardStats;
import com.docflow.api.dto.DocumentCursor;
import com.docflow.api.dto.DocumentsPage;
import com.docflow.api.dto.ProcessingItem;
import com.docflow.workflow.WorkflowStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "docflow.feature.dashboard-stub",
    havingValue = "true",
    matchIfMissing = false)
class StubDashboardRepository implements DashboardRepository {

  @Override
  public List<ProcessingItem> listProcessing(String orgId) {
    return List.of();
  }

  @Override
  public DocumentsPage listDocumentsPage(
      String orgId,
      Optional<WorkflowStatus> statusFilter,
      Optional<String> stageDisplayNameFilter,
      Optional<String> docTypeFilter,
      Optional<DocumentCursor> cursor,
      int pageSize) {
    return new DocumentsPage(List.of(), null);
  }

  @Override
  public DashboardStats stats(String orgId) {
    return new DashboardStats(0L, 0L, 0L, 0L);
  }
}
