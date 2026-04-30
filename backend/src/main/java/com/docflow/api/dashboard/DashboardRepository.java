package com.docflow.api.dashboard;

import com.docflow.api.dto.DashboardStats;
import com.docflow.api.dto.DocumentCursor;
import com.docflow.api.dto.DocumentView;
import com.docflow.api.dto.DocumentsPage;
import com.docflow.api.dto.ProcessingItem;
import com.docflow.workflow.WorkflowStatus;
import java.util.List;
import java.util.Optional;

public interface DashboardRepository {

  int DEFAULT_DOCUMENTS_PAGE_SIZE = 200;

  List<ProcessingItem> listProcessing(String orgId);

  default List<DocumentView> listDocuments(
      String orgId,
      Optional<WorkflowStatus> statusFilter,
      Optional<String> stageDisplayNameFilter,
      Optional<String> docTypeFilter) {
    return listDocumentsPage(
            orgId,
            statusFilter,
            stageDisplayNameFilter,
            docTypeFilter,
            Optional.empty(),
            DEFAULT_DOCUMENTS_PAGE_SIZE)
        .items();
  }

  DocumentsPage listDocumentsPage(
      String orgId,
      Optional<WorkflowStatus> statusFilter,
      Optional<String> stageDisplayNameFilter,
      Optional<String> docTypeFilter,
      Optional<DocumentCursor> cursor,
      int pageSize);

  DashboardStats stats(String orgId);
}
