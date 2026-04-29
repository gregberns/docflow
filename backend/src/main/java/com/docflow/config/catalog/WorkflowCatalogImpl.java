package com.docflow.config.catalog;

import com.docflow.config.persistence.StageEntity;
import com.docflow.config.persistence.StageRepository;
import com.docflow.config.persistence.TransitionEntity;
import com.docflow.config.persistence.TransitionRepository;
import com.docflow.config.persistence.WorkflowEntity;
import com.docflow.config.persistence.WorkflowRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@DependsOn("orgConfigSeeder")
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
public class WorkflowCatalogImpl implements WorkflowCatalog {

  private final WorkflowRepository workflowRepository;
  private final StageRepository stageRepository;
  private final TransitionRepository transitionRepository;

  private volatile Map<String, Map<String, WorkflowView>> snapshot;

  public WorkflowCatalogImpl(
      WorkflowRepository workflowRepository,
      StageRepository stageRepository,
      TransitionRepository transitionRepository) {
    this.workflowRepository = workflowRepository;
    this.stageRepository = stageRepository;
    this.transitionRepository = transitionRepository;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.LOWEST_PRECEDENCE)
  public void loadOnReady() {
    this.snapshot = load();
  }

  private Map<String, Map<String, WorkflowView>> snapshot() {
    Map<String, Map<String, WorkflowView>> local = snapshot;
    if (local == null) {
      throw new IllegalStateException("catalog not loaded — ApplicationReadyEvent has not fired");
    }
    return local;
  }

  private Map<String, Map<String, WorkflowView>> load() {
    Map<String, Map<String, WorkflowView>> assembled = new LinkedHashMap<>();
    for (WorkflowEntity workflow : workflowRepository.findAll()) {
      String orgId = workflow.getOrganizationId();
      String docTypeId = workflow.getDocumentTypeId();

      List<StageView> stages = loadStages(orgId, docTypeId);
      List<TransitionView> transitions = loadTransitions(orgId, docTypeId);

      WorkflowView view = new WorkflowView(orgId, docTypeId, stages, transitions);
      assembled.computeIfAbsent(orgId, k -> new LinkedHashMap<>()).put(docTypeId, view);
    }

    Map<String, Map<String, WorkflowView>> immutable = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, WorkflowView>> e : assembled.entrySet()) {
      immutable.put(e.getKey(), Map.copyOf(e.getValue()));
    }
    return Map.copyOf(immutable);
  }

  private List<StageView> loadStages(String orgId, String docTypeId) {
    List<StageEntity> entities =
        stageRepository.findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(orgId, docTypeId);
    List<StageView> views = new ArrayList<>(entities.size());
    for (StageEntity entity : entities) {
      views.add(
          new StageView(
              entity.getId(),
              entity.getDisplayName(),
              entity.getKind(),
              entity.getCanonicalStatus(),
              entity.getRole()));
    }
    return views;
  }

  private List<TransitionView> loadTransitions(String orgId, String docTypeId) {
    List<TransitionEntity> entities =
        transitionRepository.findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(
            orgId, docTypeId);
    List<TransitionView> views = new ArrayList<>(entities.size());
    for (TransitionEntity entity : entities) {
      GuardView guard =
          entity.getGuardField() == null
              ? null
              : new GuardView(entity.getGuardField(), entity.getGuardOp(), entity.getGuardValue());
      views.add(
          new TransitionView(
              entity.getFromStage(), entity.getToStage(), entity.getAction(), guard));
    }
    return views;
  }

  @Override
  public Optional<WorkflowView> getWorkflow(String orgId, String docTypeId) {
    Map<String, WorkflowView> orgMap = snapshot().get(orgId);
    if (orgMap == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(orgMap.get(docTypeId));
  }
}
