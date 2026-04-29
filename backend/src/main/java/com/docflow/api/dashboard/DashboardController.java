package com.docflow.api.dashboard;

import com.docflow.api.dto.DashboardResponse;
import com.docflow.api.dto.DashboardStats;
import com.docflow.api.dto.DocumentView;
import com.docflow.api.dto.ProcessingItem;
import com.docflow.api.error.DocflowException.FieldError;
import com.docflow.api.error.UnknownOrganizationException;
import com.docflow.api.error.ValidationException;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.workflow.WorkflowStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizations/{orgId}/documents")
public class DashboardController {

  private final OrganizationCatalog organizationCatalog;
  private final DashboardRepository dashboardRepository;

  public DashboardController(
      OrganizationCatalog organizationCatalog, DashboardRepository dashboardRepository) {
    this.organizationCatalog = organizationCatalog;
    this.dashboardRepository = dashboardRepository;
  }

  @GetMapping
  public DashboardResponse dashboard(
      @PathVariable String orgId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String docType) {
    if (organizationCatalog.getOrganization(orgId).isEmpty()) {
      throw new UnknownOrganizationException(orgId);
    }

    Optional<WorkflowStatus> statusFilter = parseStatus(status);
    Optional<String> docTypeFilter =
        (docType == null || docType.isBlank()) ? Optional.empty() : Optional.of(docType);

    List<ProcessingItem> processing = dashboardRepository.listProcessing(orgId);
    List<DocumentView> documents =
        dashboardRepository.listDocuments(orgId, statusFilter, docTypeFilter);
    DashboardStats stats = dashboardRepository.stats(orgId);

    return new DashboardResponse(processing, documents, stats);
  }

  private static Optional<WorkflowStatus> parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(WorkflowStatus.valueOf(status));
    } catch (IllegalArgumentException ex) {
      String allowed =
          Arrays.stream(WorkflowStatus.values()).map(Enum::name).collect(Collectors.joining(", "));
      throw new ValidationException(
          "Invalid status filter", List.of(new FieldError("status", "must be one of: " + allowed)));
    }
  }
}
