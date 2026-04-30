package com.docflow.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.config.catalog.GuardView;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.TransitionView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TransitionResolverTest {

  private static final String ORG = "ironworks-construction";
  private static final String DOC_TYPE = "lien-waiver";
  private static final String STAGE_REVIEW = "review";
  private static final String STAGE_FILED = "filed";
  private static final String STAGE_PM_APPROVAL = "project-manager-approval";
  private static final String STAGE_REJECTED = "rejected";

  @Test
  void approveFromReviewWithUnconditionalWaiverResolvesToFiledTransition() {
    WorkflowCatalog catalog =
        catalog(
            new WorkflowView(
                ORG,
                DOC_TYPE,
                stages(),
                List.of(
                    new TransitionView(
                        STAGE_REVIEW,
                        STAGE_FILED,
                        "APPROVE",
                        new GuardView("waiverType", "EQ", "unconditional")),
                    new TransitionView(
                        STAGE_REVIEW,
                        STAGE_PM_APPROVAL,
                        "APPROVE",
                        new GuardView("waiverType", "NEQ", "unconditional")))));

    TransitionResolver.Result result =
        new TransitionResolver(catalog)
            .resolve(
                ORG,
                DOC_TYPE,
                STAGE_REVIEW,
                new WorkflowAction.Approve(),
                Map.of("waiverType", "unconditional"));

    assertThat(result).isInstanceOf(TransitionResolver.Result.Match.class);
    TransitionResolver.Result.Match match = (TransitionResolver.Result.Match) result;
    assertThat(match.transition().toStage()).isEqualTo(STAGE_FILED);
  }

  @Test
  void approveFromReviewWithConditionalWaiverResolvesToProjectManagerApproval() {
    WorkflowCatalog catalog =
        catalog(
            new WorkflowView(
                ORG,
                DOC_TYPE,
                stages(),
                List.of(
                    new TransitionView(
                        STAGE_REVIEW,
                        STAGE_FILED,
                        "APPROVE",
                        new GuardView("waiverType", "EQ", "unconditional")),
                    new TransitionView(
                        STAGE_REVIEW,
                        STAGE_PM_APPROVAL,
                        "APPROVE",
                        new GuardView("waiverType", "NEQ", "unconditional")))));

    TransitionResolver.Result result =
        new TransitionResolver(catalog)
            .resolve(
                ORG,
                DOC_TYPE,
                STAGE_REVIEW,
                new WorkflowAction.Approve(),
                Map.of("waiverType", "conditional"));

    assertThat(result).isInstanceOf(TransitionResolver.Result.Match.class);
    TransitionResolver.Result.Match match = (TransitionResolver.Result.Match) result;
    assertThat(match.transition().toStage()).isEqualTo(STAGE_PM_APPROVAL);
  }

  @Test
  void approveFromTerminalStageReturnsInvalidActionWithStageId() {
    WorkflowCatalog catalog =
        catalog(
            new WorkflowView(
                ORG,
                DOC_TYPE,
                stages(),
                List.of(
                    new TransitionView(STAGE_REVIEW, STAGE_FILED, "APPROVE", null),
                    new TransitionView(STAGE_REVIEW, STAGE_REJECTED, "REJECT", null))));

    TransitionResolver.Result result =
        new TransitionResolver(catalog)
            .resolve(ORG, DOC_TYPE, STAGE_FILED, new WorkflowAction.Approve(), Map.of());

    assertThat(result).isInstanceOf(TransitionResolver.Result.Invalid.class);
    TransitionResolver.Result.Invalid invalid = (TransitionResolver.Result.Invalid) result;
    assertThat(invalid.error().currentStageId()).isEqualTo(STAGE_FILED);
    assertThat(invalid.error().actionType()).isEqualTo("APPROVE");
  }

  @Test
  void approveFromUnknownCurrentStageReturnsInvalidAction() {
    WorkflowCatalog catalog =
        catalog(
            new WorkflowView(
                ORG,
                DOC_TYPE,
                stages(),
                List.of(new TransitionView(STAGE_REVIEW, STAGE_FILED, "APPROVE", null))));

    TransitionResolver.Result result =
        new TransitionResolver(catalog)
            .resolve(ORG, DOC_TYPE, "ghost-stage", new WorkflowAction.Approve(), Map.of());

    assertThat(result).isInstanceOf(TransitionResolver.Result.Invalid.class);
    TransitionResolver.Result.Invalid invalid = (TransitionResolver.Result.Invalid) result;
    assertThat(invalid.error().currentStageId()).isEqualTo("ghost-stage");
    assertThat(invalid.error().actionType()).isEqualTo("APPROVE");
  }

  @Test
  void missingWorkflowReturnsInvalidAction() {
    WorkflowCatalog catalog = (org, docType) -> Optional.empty();

    TransitionResolver.Result result =
        new TransitionResolver(catalog)
            .resolve(ORG, DOC_TYPE, STAGE_REVIEW, new WorkflowAction.Reject(), Map.of());

    assertThat(result).isInstanceOf(TransitionResolver.Result.Invalid.class);
    TransitionResolver.Result.Invalid invalid = (TransitionResolver.Result.Invalid) result;
    assertThat(invalid.error().currentStageId()).isEqualTo(STAGE_REVIEW);
    assertThat(invalid.error().actionType()).isEqualTo("REJECT");
  }

  @Test
  void firstMatchingGuardWinsWhenFiledIsListedFirst() {
    WorkflowCatalog catalog =
        catalog(
            new WorkflowView(
                ORG,
                DOC_TYPE,
                stages(),
                List.of(
                    new TransitionView(
                        STAGE_REVIEW,
                        STAGE_FILED,
                        "APPROVE",
                        new GuardView("waiverType", "EQ", "unconditional")),
                    new TransitionView(
                        STAGE_REVIEW,
                        STAGE_PM_APPROVAL,
                        "APPROVE",
                        new GuardView("waiverType", "NEQ", "unconditional")))));

    TransitionResolver.Result result =
        new TransitionResolver(catalog)
            .resolve(
                ORG,
                DOC_TYPE,
                STAGE_REVIEW,
                new WorkflowAction.Approve(),
                Map.of("waiverType", "unconditional"));

    assertThat(((TransitionResolver.Result.Match) result).transition().toStage())
        .isEqualTo(STAGE_FILED);
  }

  @Test
  void firstMatchingGuardWinsWhenProjectManagerApprovalIsListedFirst() {
    WorkflowCatalog catalog =
        catalog(
            new WorkflowView(
                ORG,
                DOC_TYPE,
                stages(),
                List.of(
                    new TransitionView(
                        STAGE_REVIEW,
                        STAGE_PM_APPROVAL,
                        "APPROVE",
                        new GuardView("waiverType", "NEQ", "unconditional")),
                    new TransitionView(
                        STAGE_REVIEW,
                        STAGE_FILED,
                        "APPROVE",
                        new GuardView("waiverType", "EQ", "unconditional")))));

    TransitionResolver.Result result =
        new TransitionResolver(catalog)
            .resolve(
                ORG,
                DOC_TYPE,
                STAGE_REVIEW,
                new WorkflowAction.Approve(),
                Map.of("waiverType", "unconditional"));

    assertThat(((TransitionResolver.Result.Match) result).transition().toStage())
        .isEqualTo(STAGE_FILED);
  }

  @Test
  void flagActionResolvesTransitionWithoutInspectingComment() {
    WorkflowCatalog catalog =
        catalog(
            new WorkflowView(
                ORG,
                DOC_TYPE,
                stages(),
                List.of(new TransitionView(STAGE_PM_APPROVAL, STAGE_REVIEW, "FLAG", null))));

    TransitionResolver.Result emptyComment =
        new TransitionResolver(catalog)
            .resolve(ORG, DOC_TYPE, STAGE_PM_APPROVAL, new WorkflowAction.Flag(""), Map.of());
    TransitionResolver.Result blankComment =
        new TransitionResolver(catalog)
            .resolve(ORG, DOC_TYPE, STAGE_PM_APPROVAL, new WorkflowAction.Flag("   "), Map.of());
    TransitionResolver.Result populatedComment =
        new TransitionResolver(catalog)
            .resolve(
                ORG,
                DOC_TYPE,
                STAGE_PM_APPROVAL,
                new WorkflowAction.Flag("needs paperwork"),
                Map.of());

    assertThat(((TransitionResolver.Result.Match) emptyComment).transition().toStage())
        .isEqualTo(STAGE_REVIEW);
    assertThat(((TransitionResolver.Result.Match) blankComment).transition().toStage())
        .isEqualTo(STAGE_REVIEW);
    assertThat(((TransitionResolver.Result.Match) populatedComment).transition().toStage())
        .isEqualTo(STAGE_REVIEW);
  }

  @Test
  void resolveActionMapsToResolveActionString() {
    WorkflowCatalog catalog =
        catalog(
            new WorkflowView(
                ORG,
                DOC_TYPE,
                stages(),
                List.of(new TransitionView(STAGE_REVIEW, STAGE_PM_APPROVAL, "RESOLVE", null))));

    TransitionResolver.Result result =
        new TransitionResolver(catalog)
            .resolve(ORG, DOC_TYPE, STAGE_REVIEW, new WorkflowAction.Resolve("invoice"), Map.of());

    assertThat(((TransitionResolver.Result.Match) result).transition().toStage())
        .isEqualTo(STAGE_PM_APPROVAL);
  }

  @Test
  void unknownGuardOpThrowsIllegalArgumentException() {
    WorkflowCatalog catalog =
        catalog(
            new WorkflowView(
                ORG,
                DOC_TYPE,
                stages(),
                List.of(
                    new TransitionView(
                        STAGE_REVIEW,
                        STAGE_FILED,
                        "APPROVE",
                        new GuardView("waiverType", "EQUALS", "unconditional")))));

    assertThatThrownBy(
            () ->
                new TransitionResolver(catalog)
                    .resolve(
                        ORG,
                        DOC_TYPE,
                        STAGE_REVIEW,
                        new WorkflowAction.Approve(),
                        Map.of("waiverType", "unconditional")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EQUALS")
        .hasMessageContaining("EQ")
        .hasMessageContaining("NEQ");
  }

  @Test
  void rejectFromReviewWithNullGuardMatches() {
    WorkflowCatalog catalog =
        catalog(
            new WorkflowView(
                ORG,
                DOC_TYPE,
                stages(),
                List.of(new TransitionView(STAGE_REVIEW, STAGE_REJECTED, "REJECT", null))));

    TransitionResolver.Result result =
        new TransitionResolver(catalog)
            .resolve(ORG, DOC_TYPE, STAGE_REVIEW, new WorkflowAction.Reject(), null);

    assertThat(((TransitionResolver.Result.Match) result).transition().toStage())
        .isEqualTo(STAGE_REJECTED);
  }

  private static WorkflowCatalog catalog(WorkflowView view) {
    return (org, docType) ->
        org.equals(view.organizationId()) && docType.equals(view.documentTypeId())
            ? Optional.of(view)
            : Optional.empty();
  }

  private static List<StageView> stages() {
    return List.of(
        new StageView(STAGE_REVIEW, STAGE_REVIEW, "REVIEW", "AWAITING_REVIEW", null),
        new StageView(STAGE_PM_APPROVAL, STAGE_PM_APPROVAL, "APPROVAL", "AWAITING_APPROVAL", "pm"),
        new StageView(STAGE_FILED, STAGE_FILED, "TERMINAL", "FILED", null),
        new StageView(STAGE_REJECTED, STAGE_REJECTED, "TERMINAL", "REJECTED", null));
  }
}
