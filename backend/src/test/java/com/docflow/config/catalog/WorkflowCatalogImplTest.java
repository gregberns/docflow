package com.docflow.config.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.docflow.config.persistence.StageEntity;
import com.docflow.config.persistence.StageRepository;
import com.docflow.config.persistence.TransitionEntity;
import com.docflow.config.persistence.TransitionRepository;
import com.docflow.config.persistence.WorkflowEntity;
import com.docflow.config.persistence.WorkflowRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowCatalogImplTest {

  private static final String ORG = "test-construction";
  private static final String DOC_TYPE = "lien-waiver";

  @Test
  void loadFailsWhenTransitionDeclaresUnknownGuardOp() {
    WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    StageRepository stageRepository = mock(StageRepository.class);
    TransitionRepository transitionRepository = mock(TransitionRepository.class);

    when(workflowRepository.findAll()).thenReturn(List.of(new WorkflowEntity(ORG, DOC_TYPE)));
    when(stageRepository.findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(ORG, DOC_TYPE))
        .thenReturn(
            List.of(
                new StageEntity(
                    ORG, DOC_TYPE, "Review", "Review", "REVIEW", "AWAITING_REVIEW", null, 0),
                new StageEntity(ORG, DOC_TYPE, "Filed", "Filed", "TERMINAL", "FILED", null, 1)));
    when(transitionRepository.findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(ORG, DOC_TYPE))
        .thenReturn(
            List.of(
                new TransitionEntity(
                    UUID.randomUUID(),
                    new TransitionEntity.TransitionKey(ORG, DOC_TYPE, "Review", "Filed", "APPROVE"),
                    new TransitionEntity.TransitionGuard("waiverType", "EQUALS", "unconditional"),
                    0)));

    WorkflowCatalogImpl catalog =
        new WorkflowCatalogImpl(workflowRepository, stageRepository, transitionRepository);

    assertThatThrownBy(catalog::loadOnReady)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ORG)
        .hasMessageContaining(DOC_TYPE)
        .hasMessageContaining("EQUALS")
        .hasMessageContaining("EQ")
        .hasMessageContaining("NEQ");
  }

  @Test
  void loadAcceptsKnownGuardOps() {
    WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    StageRepository stageRepository = mock(StageRepository.class);
    TransitionRepository transitionRepository = mock(TransitionRepository.class);

    when(workflowRepository.findAll()).thenReturn(List.of(new WorkflowEntity(ORG, DOC_TYPE)));
    when(stageRepository.findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(ORG, DOC_TYPE))
        .thenReturn(
            List.of(
                new StageEntity(
                    ORG, DOC_TYPE, "Review", "Review", "REVIEW", "AWAITING_REVIEW", null, 0),
                new StageEntity(ORG, DOC_TYPE, "Filed", "Filed", "TERMINAL", "FILED", null, 1)));
    when(transitionRepository.findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(ORG, DOC_TYPE))
        .thenReturn(
            List.of(
                new TransitionEntity(
                    UUID.randomUUID(),
                    new TransitionEntity.TransitionKey(ORG, DOC_TYPE, "Review", "Filed", "APPROVE"),
                    new TransitionEntity.TransitionGuard("waiverType", "EQ", "unconditional"),
                    0),
                new TransitionEntity(
                    UUID.randomUUID(),
                    new TransitionEntity.TransitionKey(ORG, DOC_TYPE, "Review", "Filed", "REJECT"),
                    new TransitionEntity.TransitionGuard("waiverType", "NEQ", "unconditional"),
                    1)));

    WorkflowCatalogImpl catalog =
        new WorkflowCatalogImpl(workflowRepository, stageRepository, transitionRepository);
    catalog.loadOnReady();

    WorkflowView view = catalog.getWorkflow(ORG, DOC_TYPE).orElseThrow();
    assertThat(view.transitions()).hasSize(2);
    assertThat(view.transitions()).extracting(t -> t.guard().op()).containsExactly("EQ", "NEQ");
  }
}
