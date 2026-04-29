package com.docflow.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.docflow.c3.llm.LlmExtractor;
import com.docflow.config.catalog.GuardView;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.TransitionView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.ReextractionStatus;
import com.docflow.platform.DocumentEvent;
import com.docflow.platform.DocumentEventBus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.springframework.context.ApplicationEventPublisher;

class WorkflowEnginePropertyTest {

  private static final String ORG_ID = "acme-orga";
  private static final String DOC_TYPE = "invoice";
  private static final String NEW_DOC_TYPE = "receipt";
  private static final String ACTION_APPROVE = "APPROVE";
  private static final String ACTION_REJECT = "REJECT";
  private static final String ACTION_FLAG = "FLAG";
  private static final String STAGE_KIND_REVIEW = "review";
  private static final String STAGE_KIND_APPROVAL = "approval";
  private static final String STAGE_KIND_TERMINAL = "terminal";
  private static final String STATUS_AWAITING_REVIEW = "AWAITING_REVIEW";
  private static final String STATUS_AWAITING_APPROVAL = "AWAITING_APPROVAL";
  private static final String STATUS_FILED = "FILED";
  private static final String STATUS_REJECTED = "REJECTED";
  private static final String GUARD_FIELD = "amount";
  private static final String GUARD_VALUE = "high";
  private static final Instant FIXED_NOW = Instant.parse("2026-04-28T12:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final int APPROVE_STEP_LIMIT = 64;
  private static final WorkflowAction APPROVE = new WorkflowAction.Approve();

  @Property(tries = 200)
  void alwaysApproveReachesTerminalFromAnyNonTerminalStage(
      @ForAll("linearTopologies") LinearTopology topology) {
    for (StageView origin : topology.nonTerminalStages()) {
      Fixture fx = newFixture(topology.toView(), origin, ReextractionStatus.NONE);
      WorkflowOutcome outcome = applyApproveUntilTerminal(fx, topology);
      assertThat(outcome).isInstanceOf(WorkflowOutcome.Success.class);
      WorkflowInstance result = ((WorkflowOutcome.Success) outcome).instance();
      assertThat(topology.kindOf(result.currentStageId())).isEqualTo(STAGE_KIND_TERMINAL);
    }
  }

  @Property(tries = 200)
  void flagThenResolveWithoutTypeChangeReturnsToOriginAndClearsFlag(
      @ForAll("linearTopologies") LinearTopology topology,
      @ForAll @IntRange(min = 0, max = 7) int originPick) {
    List<StageView> approvals = topology.approvalStages();
    if (approvals.isEmpty()) {
      return;
    }
    StageView origin = approvals.get(originPick % approvals.size());
    Fixture fx = newFixture(topology.toView(), origin, ReextractionStatus.NONE);

    WorkflowOutcome flagOutcome =
        fx.engine().applyAction(fx.documentId(), new WorkflowAction.Flag("needs receipt"));
    assertThat(flagOutcome).isInstanceOf(WorkflowOutcome.Success.class);
    WorkflowInstance flagged = ((WorkflowOutcome.Success) flagOutcome).instance();
    assertThat(topology.kindOf(flagged.currentStageId())).isEqualTo(STAGE_KIND_REVIEW);
    assertThat(flagged.workflowOriginStage()).isEqualTo(origin.id());
    assertThat(flagged.currentStatus()).isEqualTo(WorkflowStatus.FLAGGED);
    assertThat(flagged.flagComment()).isEqualTo("needs receipt");

    WorkflowOutcome resolveOutcome =
        fx.engine().applyAction(fx.documentId(), new WorkflowAction.Resolve(null));
    assertThat(resolveOutcome).isInstanceOf(WorkflowOutcome.Success.class);
    WorkflowInstance resolved = ((WorkflowOutcome.Success) resolveOutcome).instance();
    assertThat(resolved.currentStageId()).isEqualTo(origin.id());
    assertThat(resolved.workflowOriginStage()).isNull();
    assertThat(resolved.flagComment()).isNull();
    assertThat(resolved.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_APPROVAL);
  }

  @Property(tries = 200)
  void flagThenResolveWithTypeChangeStaysInReviewAndClearsOrigin(
      @ForAll("linearTopologies") LinearTopology topology,
      @ForAll @IntRange(min = 0, max = 7) int originPick) {
    List<StageView> approvals = topology.approvalStages();
    if (approvals.isEmpty()) {
      return;
    }
    StageView origin = approvals.get(originPick % approvals.size());
    Fixture fx = newFixture(topology.toView(), origin, ReextractionStatus.NONE);

    WorkflowOutcome flagOutcome =
        fx.engine().applyAction(fx.documentId(), new WorkflowAction.Flag("retype me"));
    assertThat(flagOutcome).isInstanceOf(WorkflowOutcome.Success.class);

    WorkflowOutcome resolveOutcome =
        fx.engine().applyAction(fx.documentId(), new WorkflowAction.Resolve(NEW_DOC_TYPE));
    assertThat(resolveOutcome).isInstanceOf(WorkflowOutcome.Success.class);

    WorkflowInstance midFlight = fx.currentInstance();
    assertThat(topology.kindOf(midFlight.currentStageId())).isEqualTo(STAGE_KIND_REVIEW);
    assertThat(midFlight.workflowOriginStage()).isEqualTo(origin.id());

    fx.simulateExtractionCompleted(NEW_DOC_TYPE);
    WorkflowInstance afterClear = fx.currentInstance();
    assertThat(topology.kindOf(afterClear.currentStageId())).isEqualTo(STAGE_KIND_REVIEW);
    assertThat(afterClear.workflowOriginStage()).isNull();
    assertThat(afterClear.flagComment()).isNull();
    assertThat(afterClear.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_REVIEW);
  }

  @Property(tries = 200)
  void terminalStagesRejectEveryAction(
      @ForAll("linearTopologies") LinearTopology topology,
      @ForAll @IntRange(min = 0, max = 3) int actionPick) {
    for (StageView terminal : topology.terminalStages()) {
      Fixture fx = newFixture(topology.toView(), terminal, ReextractionStatus.NONE);
      WorkflowAction action = pickAction(actionPick);
      WorkflowOutcome outcome = fx.engine().applyAction(fx.documentId(), action);
      assertThat(outcome).isInstanceOf(WorkflowOutcome.Failure.class);
      WorkflowError error = ((WorkflowOutcome.Failure) outcome).error();
      assertThat(error).isInstanceOf(WorkflowError.InvalidAction.class);
      WorkflowError.InvalidAction invalid = (WorkflowError.InvalidAction) error;
      assertThat(invalid.currentStageId()).isEqualTo(terminal.id());
    }
  }

  @Property(tries = 200)
  void guardsRouteAccordingToValuation(@ForAll("guardValuations") String value) {
    GuardedTopology topology = new GuardedTopology();
    Fixture fx = newGuardedFixture(topology, value);
    WorkflowOutcome outcome =
        fx.engine().applyAction(fx.documentId(), new WorkflowAction.Approve());
    assertThat(outcome).isInstanceOf(WorkflowOutcome.Success.class);
    WorkflowInstance updated = ((WorkflowOutcome.Success) outcome).instance();
    String expected = GUARD_VALUE.equals(value) ? topology.eqStageId() : topology.neqStageId();
    assertThat(updated.currentStageId()).isEqualTo(expected);
  }

  @Property(tries = 200)
  void everyWriteSatisfiesC4R12StatusRule(@ForAll("linearTopologies") LinearTopology topology) {
    for (StageView origin : topology.nonTerminalStages()) {
      Fixture fx = newFixture(topology.toView(), origin, ReextractionStatus.NONE);
      driveAndAssertStatusRule(fx, topology, origin);
    }
  }

  private static void driveAndAssertStatusRule(
      Fixture fx, LinearTopology topology, StageView origin) {
    fx.assertC4R12();
    if (STAGE_KIND_APPROVAL.equals(origin.kind())) {
      WorkflowOutcome flagOutcome =
          fx.engine().applyAction(fx.documentId(), new WorkflowAction.Flag("look here"));
      assertThat(flagOutcome).isInstanceOf(WorkflowOutcome.Success.class);
      fx.assertC4R12();
      WorkflowOutcome resolveOutcome =
          fx.engine().applyAction(fx.documentId(), new WorkflowAction.Resolve(null));
      assertThat(resolveOutcome).isInstanceOf(WorkflowOutcome.Success.class);
      fx.assertC4R12();
    }

    int steps = 0;
    while (steps < APPROVE_STEP_LIMIT) {
      WorkflowInstance current = fx.currentInstance();
      if (STAGE_KIND_TERMINAL.equals(topology.kindOf(current.currentStageId()))) {
        break;
      }
      WorkflowOutcome outcome = fx.engine().applyAction(fx.documentId(), APPROVE);
      assertThat(outcome).isInstanceOf(WorkflowOutcome.Success.class);
      fx.assertC4R12();
      steps++;
    }
    assertThat(steps).isLessThan(APPROVE_STEP_LIMIT);
  }

  @Provide
  Arbitrary<LinearTopology> linearTopologies() {
    Arbitrary<Integer> approvalCount = Arbitraries.integers().between(0, 6);
    Arbitrary<Boolean> includeRejected = Arbitraries.of(true, false);
    return Combinators.combine(approvalCount, includeRejected).as(LinearTopology::new);
  }

  @Provide
  Arbitrary<String> guardValuations() {
    return Arbitraries.of(GUARD_VALUE, "low", "medium", "");
  }

  private static WorkflowAction pickAction(int pick) {
    return switch (pick % 4) {
      case 0 -> new WorkflowAction.Approve();
      case 1 -> new WorkflowAction.Reject();
      case 2 -> new WorkflowAction.Flag("comment");
      default -> new WorkflowAction.Resolve(null);
    };
  }

  private static WorkflowOutcome applyApproveUntilTerminal(Fixture fx, LinearTopology topology) {
    WorkflowOutcome last = null;
    WorkflowInstance instance = fx.currentInstance();
    if (STAGE_KIND_TERMINAL.equals(topology.kindOf(instance.currentStageId()))) {
      return new WorkflowOutcome.Success(instance);
    }
    for (int step = 0; step < APPROVE_STEP_LIMIT; step++) {
      last = fx.engine().applyAction(fx.documentId(), APPROVE);
      if (last instanceof WorkflowOutcome.Failure) {
        return last;
      }
      if (STAGE_KIND_TERMINAL.equals(topology.kindOf(fx.currentInstance().currentStageId()))) {
        return last;
      }
    }
    throw new IllegalStateException("approve loop did not terminate");
  }

  private static Fixture newFixture(
      WorkflowView view, StageView startStage, ReextractionStatus reextractionStatus) {
    InMemoryStore store = new InMemoryStore(view);
    WorkflowCatalog catalog =
        (org, type) ->
            org.equals(view.organizationId()) && type.equals(view.documentTypeId())
                ? Optional.of(view)
                : Optional.empty();
    UUID documentId = UUID.randomUUID();
    UUID storedDocumentId = UUID.randomUUID();
    Document document =
        new Document(
            documentId,
            storedDocumentId,
            ORG_ID,
            DOC_TYPE,
            Map.of(GUARD_FIELD, "low"),
            "raw text",
            FIXED_NOW,
            reextractionStatus);
    store.putDocument(document);
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(),
            documentId,
            ORG_ID,
            startStage.id(),
            store.deriveStatus(startStage, null),
            null,
            null,
            FIXED_NOW);
    store.putInstance(instance);

    LlmExtractor llmExtractor = mock(LlmExtractor.class);
    DocumentEventBus eventBus = new RecordingEventBus();
    WorkflowInstanceWriter writer = store.instanceWriter();
    WorkflowEngine engine =
        new WorkflowEngine(
            catalog,
            store.documentReader(),
            store.instanceReader(),
            writer,
            llmExtractor,
            eventBus,
            FIXED_CLOCK);
    return new Fixture(engine, store, documentId, view);
  }

  private static Fixture newGuardedFixture(GuardedTopology topology, String value) {
    WorkflowView view = topology.toView();
    InMemoryStore store = new InMemoryStore(view);
    WorkflowCatalog catalog =
        (org, type) ->
            org.equals(view.organizationId()) && type.equals(view.documentTypeId())
                ? Optional.of(view)
                : Optional.empty();
    UUID documentId = UUID.randomUUID();
    Map<String, Object> fields = new HashMap<>();
    fields.put(GUARD_FIELD, value);
    Document document =
        new Document(
            documentId,
            UUID.randomUUID(),
            ORG_ID,
            DOC_TYPE,
            fields,
            "raw text",
            FIXED_NOW,
            ReextractionStatus.NONE);
    store.putDocument(document);
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(),
            documentId,
            ORG_ID,
            topology.reviewStageId(),
            WorkflowStatus.AWAITING_REVIEW,
            null,
            null,
            FIXED_NOW);
    store.putInstance(instance);
    LlmExtractor llmExtractor = mock(LlmExtractor.class);
    DocumentEventBus eventBus = new RecordingEventBus();
    WorkflowInstanceWriter writer = store.instanceWriter();
    WorkflowEngine engine =
        new WorkflowEngine(
            catalog,
            store.documentReader(),
            store.instanceReader(),
            writer,
            llmExtractor,
            eventBus,
            FIXED_CLOCK);
    return new Fixture(engine, store, documentId, view);
  }

  private record Fixture(
      WorkflowEngine engine, InMemoryStore store, UUID documentId, WorkflowView view) {

    WorkflowInstance currentInstance() {
      return store.instance(documentId);
    }

    void simulateExtractionCompleted(String newDocType) {
      Document doc = store.document(documentId);
      store.putDocument(
          new Document(
              doc.id(),
              doc.storedDocumentId(),
              doc.organizationId(),
              newDocType,
              doc.extractedFields(),
              doc.rawText(),
              doc.processedAt(),
              ReextractionStatus.NONE));
      WorkflowInstance prior = store.instance(documentId);
      StageView review = store.reviewStage();
      store.putInstance(
          new WorkflowInstance(
              prior.id(),
              prior.documentId(),
              prior.organizationId(),
              review.id(),
              store.deriveStatus(review, null),
              null,
              null,
              FIXED_NOW));
    }

    void assertC4R12() {
      WorkflowInstance instance = currentInstance();
      StageView stage = stageOf(instance.currentStageId());
      WorkflowStatus expected = expectedStatus(stage, instance.workflowOriginStage());
      assertThat(instance.currentStatus()).isEqualTo(expected);
    }

    private StageView stageOf(String stageId) {
      for (StageView stage : view.stages()) {
        if (stage.id().equals(stageId)) {
          return stage;
        }
      }
      throw new IllegalStateException("stage not found: " + stageId);
    }

    private static WorkflowStatus expectedStatus(StageView stage, String origin) {
      if (STAGE_KIND_REVIEW.equals(stage.kind().toLowerCase(Locale.ROOT)) && origin != null) {
        return WorkflowStatus.FLAGGED;
      }
      return WorkflowStatus.valueOf(stage.canonicalStatus());
    }
  }

  private static final class LinearTopology {
    private final List<StageView> stages;
    private final List<TransitionView> transitions;
    private final Map<String, String> kindByStageId;
    private final boolean includesRejected;

    LinearTopology(int approvalCount, boolean includeRejected) {
      this.includesRejected = includeRejected;
      List<StageView> built = new ArrayList<>();
      List<TransitionView> trans = new ArrayList<>();
      Map<String, String> kinds = new HashMap<>();

      StageView review =
          new StageView("stage-review", "Review", STAGE_KIND_REVIEW, STATUS_AWAITING_REVIEW, null);
      built.add(review);
      kinds.put(review.id(), review.kind());

      List<StageView> approvals =
          java.util.stream.IntStream.range(0, approvalCount)
              .mapToObj(LinearTopology::approvalStage)
              .toList();
      for (StageView appr : approvals) {
        built.add(appr);
        kinds.put(appr.id(), appr.kind());
      }

      StageView filed =
          new StageView("stage-filed", "Filed", STAGE_KIND_TERMINAL, STATUS_FILED, null);
      built.add(filed);
      kinds.put(filed.id(), filed.kind());

      StageView rejected = null;
      if (includeRejected) {
        rejected =
            new StageView("stage-rejected", "Rejected", STAGE_KIND_TERMINAL, STATUS_REJECTED, null);
        built.add(rejected);
        kinds.put(rejected.id(), rejected.kind());
      }

      String approveStart = approvals.isEmpty() ? filed.id() : approvals.get(0).id();
      trans.add(new TransitionView(review.id(), approveStart, ACTION_APPROVE, null));
      if (rejected != null) {
        trans.add(new TransitionView(review.id(), rejected.id(), ACTION_REJECT, null));
      }
      for (int i = 0; i < approvals.size(); i++) {
        StageView from = approvals.get(i);
        String approveTo = (i + 1 < approvals.size()) ? approvals.get(i + 1).id() : filed.id();
        trans.add(new TransitionView(from.id(), approveTo, ACTION_APPROVE, null));
        trans.add(new TransitionView(from.id(), review.id(), ACTION_FLAG, null));
        if (rejected != null) {
          trans.add(new TransitionView(from.id(), rejected.id(), ACTION_REJECT, null));
        }
      }

      this.stages = List.copyOf(built);
      this.transitions = List.copyOf(trans);
      this.kindByStageId = Map.copyOf(kinds);
    }

    WorkflowView toView() {
      return new WorkflowView(ORG_ID, DOC_TYPE, stages, transitions);
    }

    String kindOf(String stageId) {
      return kindByStageId.get(stageId);
    }

    List<StageView> nonTerminalStages() {
      List<StageView> out = new ArrayList<>();
      for (StageView s : stages) {
        if (!STAGE_KIND_TERMINAL.equals(s.kind())) {
          out.add(s);
        }
      }
      return out;
    }

    List<StageView> approvalStages() {
      List<StageView> out = new ArrayList<>();
      for (StageView s : stages) {
        if (STAGE_KIND_APPROVAL.equals(s.kind())) {
          out.add(s);
        }
      }
      return out;
    }

    List<StageView> terminalStages() {
      List<StageView> out = new ArrayList<>();
      for (StageView s : stages) {
        if (STAGE_KIND_TERMINAL.equals(s.kind())) {
          out.add(s);
        }
      }
      return out;
    }

    @Override
    public String toString() {
      return "LinearTopology(approvals="
          + approvalStages().size()
          + ", rejected="
          + includesRejected
          + ")";
    }

    private static StageView approvalStage(int index) {
      return new StageView(
          "stage-approval-" + index,
          "Approval " + index,
          STAGE_KIND_APPROVAL,
          STATUS_AWAITING_APPROVAL,
          "role-" + index);
    }
  }

  private static final class GuardedTopology {
    private final StageView review =
        new StageView("stage-review", "Review", STAGE_KIND_REVIEW, STATUS_AWAITING_REVIEW, null);
    private final StageView eqStage =
        new StageView(
            "stage-approval-eq",
            "Approval EQ",
            STAGE_KIND_APPROVAL,
            STATUS_AWAITING_APPROVAL,
            "role-eq");
    private final StageView neqStage =
        new StageView(
            "stage-approval-neq",
            "Approval NEQ",
            STAGE_KIND_APPROVAL,
            STATUS_AWAITING_APPROVAL,
            "role-neq");
    private final StageView filed =
        new StageView("stage-filed", "Filed", STAGE_KIND_TERMINAL, STATUS_FILED, null);

    WorkflowView toView() {
      List<TransitionView> transitions =
          List.of(
              new TransitionView(
                  review.id(),
                  eqStage.id(),
                  ACTION_APPROVE,
                  new GuardView(GUARD_FIELD, "EQ", GUARD_VALUE)),
              new TransitionView(
                  review.id(),
                  neqStage.id(),
                  ACTION_APPROVE,
                  new GuardView(GUARD_FIELD, "NEQ", GUARD_VALUE)),
              new TransitionView(eqStage.id(), filed.id(), ACTION_APPROVE, null),
              new TransitionView(neqStage.id(), filed.id(), ACTION_APPROVE, null));
      return new WorkflowView(
          ORG_ID, DOC_TYPE, List.of(review, eqStage, neqStage, filed), transitions);
    }

    String reviewStageId() {
      return review.id();
    }

    String eqStageId() {
      return eqStage.id();
    }

    String neqStageId() {
      return neqStage.id();
    }
  }

  private static final class InMemoryStore {
    private final Map<UUID, Document> documents = new HashMap<>();
    private final Map<UUID, WorkflowInstance> instances = new HashMap<>();
    private final WorkflowView view;

    InMemoryStore(WorkflowView view) {
      this.view = view;
    }

    void putDocument(Document document) {
      documents.put(document.id(), document);
    }

    void putInstance(WorkflowInstance instance) {
      instances.put(instance.documentId(), instance);
    }

    Document document(UUID id) {
      return documents.get(id);
    }

    WorkflowInstance instance(UUID documentId) {
      return instances.get(documentId);
    }

    DocumentReader documentReader() {
      return id -> Optional.ofNullable(documents.get(id));
    }

    WorkflowInstanceReader instanceReader() {
      return documentId -> Optional.ofNullable(instances.get(documentId));
    }

    WorkflowInstanceWriter instanceWriter() {
      return new InMemoryInstanceWriter(this);
    }

    StageView requireStage(String stageId) {
      for (StageView stage : view.stages()) {
        if (stage.id().equals(stageId)) {
          return stage;
        }
      }
      throw new IllegalStateException("stage not found: " + stageId);
    }

    StageView reviewStage() {
      for (StageView stage : view.stages()) {
        if (STAGE_KIND_REVIEW.equals(stage.kind().toLowerCase(Locale.ROOT))) {
          return stage;
        }
      }
      throw new IllegalStateException("no review stage");
    }

    WorkflowStatus deriveStatus(StageView stage, String originStage) {
      if (STAGE_KIND_REVIEW.equals(stage.kind().toLowerCase(Locale.ROOT)) && originStage != null) {
        return WorkflowStatus.FLAGGED;
      }
      return WorkflowStatus.valueOf(stage.canonicalStatus());
    }
  }

  private static final class InMemoryInstanceWriter implements WorkflowInstanceWriter {
    private final InMemoryStore store;

    InMemoryInstanceWriter(InMemoryStore store) {
      this.store = store;
    }

    @Override
    public void insert(WorkflowInstance instance, String documentTypeId) {
      store.putInstance(instance);
    }

    @Override
    public void advanceStage(
        UUID documentId, String newStageId, WorkflowCatalog cat, String orgId, String docTypeId) {
      WorkflowInstance prior = store.instance(documentId);
      StageView target = store.requireStage(newStageId);
      WorkflowStatus status = store.deriveStatus(target, prior.workflowOriginStage());
      store.putInstance(
          new WorkflowInstance(
              prior.id(),
              prior.documentId(),
              prior.organizationId(),
              newStageId,
              status,
              prior.workflowOriginStage(),
              prior.flagComment(),
              FIXED_NOW));
    }

    @Override
    public void setFlag(
        UUID documentId,
        String originStageId,
        String comment,
        WorkflowCatalog cat,
        String orgId,
        String docTypeId) {
      WorkflowInstance prior = store.instance(documentId);
      StageView review = store.reviewStage();
      WorkflowStatus status = store.deriveStatus(review, originStageId);
      store.putInstance(
          new WorkflowInstance(
              prior.id(),
              prior.documentId(),
              prior.organizationId(),
              review.id(),
              status,
              originStageId,
              comment,
              FIXED_NOW));
    }

    @Override
    public void clearFlag(UUID documentId, WorkflowCatalog cat, String orgId, String docTypeId) {
      WorkflowInstance prior = store.instance(documentId);
      StageView currentStage = store.requireStage(prior.currentStageId());
      String targetStageId;
      if (STAGE_KIND_REVIEW.equals(currentStage.kind().toLowerCase(Locale.ROOT))
          && prior.workflowOriginStage() == null) {
        targetStageId = prior.currentStageId();
      } else if (prior.workflowOriginStage() != null) {
        targetStageId = prior.workflowOriginStage();
      } else {
        targetStageId = prior.currentStageId();
      }
      StageView target = store.requireStage(targetStageId);
      WorkflowStatus status = store.deriveStatus(target, null);
      store.putInstance(
          new WorkflowInstance(
              prior.id(),
              prior.documentId(),
              prior.organizationId(),
              targetStageId,
              status,
              null,
              null,
              FIXED_NOW));
    }
  }

  private static final class RecordingEventBus extends DocumentEventBus {
    RecordingEventBus() {
      super(mock(ApplicationEventPublisher.class));
    }

    @Override
    public void publish(DocumentEvent event) {
      // why: property tests do not assert on event payloads; absorb to keep the engine wiring real.
    }
  }
}
