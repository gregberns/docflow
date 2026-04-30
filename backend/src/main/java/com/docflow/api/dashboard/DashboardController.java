package com.docflow.api.dashboard;

import com.docflow.api.dto.DashboardResponse;
import com.docflow.api.dto.DashboardStats;
import com.docflow.api.dto.DocumentCursor;
import com.docflow.api.dto.DocumentsPage;
import com.docflow.api.dto.ProcessingItem;
import com.docflow.api.error.DocflowException.FieldError;
import com.docflow.api.error.UnknownOrganizationException;
import com.docflow.api.error.ValidationException;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.workflow.WorkflowStatus;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String docType,
      @RequestParam(required = false) String cursorUpdatedAt,
      @RequestParam(required = false) String cursorId) {
    if (organizationCatalog.getOrganization(orgId).isEmpty()) {
      throw new UnknownOrganizationException(orgId);
    }

    Optional<WorkflowStatus> statusFilter = parseStatus(status);
    Optional<String> stageDisplayNameFilter =
        (stage == null || stage.isBlank()) ? Optional.empty() : Optional.of(stage);
    Optional<String> docTypeFilter =
        (docType == null || docType.isBlank()) ? Optional.empty() : Optional.of(docType);
    Optional<DocumentCursor> cursor = parseCursor(cursorUpdatedAt, cursorId);

    List<ProcessingItem> processing = dashboardRepository.listProcessing(orgId);
    DocumentsPage page =
        dashboardRepository.listDocumentsPage(
            orgId,
            statusFilter,
            stageDisplayNameFilter,
            docTypeFilter,
            cursor,
            DashboardRepository.DEFAULT_DOCUMENTS_PAGE_SIZE);
    DashboardStats stats = dashboardRepository.stats(orgId);

    return new DashboardResponse(processing, page.items(), stats, page.nextCursor());
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

  private static Optional<DocumentCursor> parseCursor(String cursorUpdatedAt, String cursorId) {
    boolean hasUpdatedAt = cursorUpdatedAt != null && !cursorUpdatedAt.isBlank();
    boolean hasId = cursorId != null && !cursorId.isBlank();
    if (!hasUpdatedAt && !hasId) {
      return Optional.empty();
    }
    if (hasUpdatedAt != hasId) {
      throw new ValidationException(
          "Invalid cursor",
          List.of(
              new FieldError("cursor", "cursorUpdatedAt and cursorId must be supplied together")));
    }
    Instant updatedAt;
    try {
      updatedAt = Instant.parse(cursorUpdatedAt);
    } catch (DateTimeParseException ex) {
      throw new ValidationException(
          "Invalid cursor", List.of(new FieldError("cursorUpdatedAt", "must be ISO-8601 instant")));
    }
    UUID id;
    try {
      id = UUID.fromString(cursorId);
    } catch (IllegalArgumentException ex) {
      throw new ValidationException(
          "Invalid cursor", List.of(new FieldError("cursorId", "must be a UUID")));
    }
    return Optional.of(new DocumentCursor(updatedAt, id));
  }
}
