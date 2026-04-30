package com.docflow.api.organization;

import com.docflow.api.dto.FieldSchema;
import com.docflow.api.dto.GuardSummary;
import com.docflow.api.dto.OrganizationDetail;
import com.docflow.api.dto.OrganizationListItem;
import com.docflow.api.dto.StageSummary;
import com.docflow.api.dto.TransitionSummary;
import com.docflow.api.dto.WorkflowSummary;
import com.docflow.api.error.UnknownOrganizationException;
import com.docflow.api.organization.OrganizationCountsRepository.Counts;
import com.docflow.config.catalog.DocumentTypeCatalog;
import com.docflow.config.catalog.DocumentTypeSchemaView;
import com.docflow.config.catalog.FieldView;
import com.docflow.config.catalog.GuardView;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.config.catalog.OrganizationView;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.TransitionView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizations")
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
public class OrganizationController {

  private final OrganizationCatalog organizationCatalog;
  private final DocumentTypeCatalog documentTypeCatalog;
  private final WorkflowCatalog workflowCatalog;
  private final OrganizationCountsRepository countsRepository;

  public OrganizationController(
      OrganizationCatalog organizationCatalog,
      DocumentTypeCatalog documentTypeCatalog,
      WorkflowCatalog workflowCatalog,
      OrganizationCountsRepository countsRepository) {
    this.organizationCatalog = organizationCatalog;
    this.documentTypeCatalog = documentTypeCatalog;
    this.workflowCatalog = workflowCatalog;
    this.countsRepository = countsRepository;
  }

  @GetMapping
  public List<OrganizationListItem> list() {
    Map<String, Counts> countsByOrg = countsRepository.countsByOrg();
    List<OrganizationView> orgs = organizationCatalog.listOrganizations();
    List<OrganizationListItem> result = new ArrayList<>(orgs.size());
    for (OrganizationView org : orgs) {
      Counts counts = countsByOrg.getOrDefault(org.id(), new Counts(0L, 0L));
      result.add(
          new OrganizationListItem(
              org.id(),
              org.displayName(),
              org.iconId(),
              org.documentTypeIds(),
              counts.inProgressCount(),
              counts.filedCount()));
    }
    return result;
  }

  @GetMapping("/{orgId}")
  public OrganizationDetail detail(@PathVariable String orgId) {
    OrganizationView org =
        organizationCatalog
            .getOrganization(orgId)
            .orElseThrow(() -> new UnknownOrganizationException(orgId));

    List<WorkflowSummary> workflows = new ArrayList<>(org.documentTypeIds().size());
    Map<String, List<FieldSchema>> fieldSchemas = new LinkedHashMap<>();

    for (String docTypeId : org.documentTypeIds()) {
      workflowCatalog
          .getWorkflow(orgId, docTypeId)
          .ifPresent(view -> workflows.add(toWorkflowSummary(view)));

      documentTypeCatalog
          .getDocumentTypeSchema(orgId, docTypeId)
          .ifPresent(view -> fieldSchemas.put(docTypeId, toFieldSchemas(view)));
    }

    return new OrganizationDetail(
        org.id(), org.displayName(), org.iconId(), org.documentTypeIds(), workflows, fieldSchemas);
  }

  private static WorkflowSummary toWorkflowSummary(WorkflowView view) {
    List<StageSummary> stages = new ArrayList<>(view.stages().size());
    for (StageView stage : view.stages()) {
      stages.add(
          new StageSummary(
              stage.id(),
              stage.displayName(),
              stage.kind(),
              stage.canonicalStatus(),
              stage.role()));
    }
    List<TransitionSummary> transitions = new ArrayList<>(view.transitions().size());
    for (TransitionView t : view.transitions()) {
      GuardView g = t.guard();
      GuardSummary guard = g == null ? null : new GuardSummary(g.field(), g.op(), g.value());
      transitions.add(new TransitionSummary(t.fromStage(), t.toStage(), t.action(), guard));
    }
    return new WorkflowSummary(view.documentTypeId(), stages, transitions);
  }

  private static List<FieldSchema> toFieldSchemas(DocumentTypeSchemaView view) {
    List<FieldSchema> out = new ArrayList<>(view.fields().size());
    for (FieldView f : view.fields()) {
      out.add(toFieldSchema(f));
    }
    return out;
  }

  private static FieldSchema toFieldSchema(FieldView f) {
    List<FieldSchema> nested = null;
    if (f.itemFields() != null) {
      nested = new ArrayList<>(f.itemFields().size());
      for (FieldView nf : f.itemFields()) {
        nested.add(toFieldSchema(nf));
      }
    }
    return new FieldSchema(f.name(), f.type(), f.required(), f.enumValues(), f.format(), nested);
  }
}
