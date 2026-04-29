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
import com.docflow.workflow.events.DocumentStateChanged;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops") // why: building views from seed YAML.
class FlagOriginRestorationTest {

  private static final String SEED_ROOT = "seed/";
  private static final String STAGE_KIND_REVIEW = "review";
  private static final String STAGE_KIND_APPROVAL = "approval";
  private static final String FLAG_ACTION = "FLAG";
  private static final Instant FIXED_NOW = Instant.parse("2026-04-29T10:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final String NEW_DOC_TYPE_FOR_RETYPE = "receipt";
  private static final String SUBSTITUTE_DOC_TYPE = "invoice";

  static Stream<ApprovalCase> approvalStages() {
    return loadSeed().stream()
        .flatMap(FlagOriginRestorationTest::expandApprovalCases)
        .sorted(
            Comparator.comparing(ApprovalCase::organizationId)
                .thenComparing(ApprovalCase::documentTypeId)
                .thenComparing(ApprovalCase::approvalStageId));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("approvalStages")
  @DisplayName("Resolve without type change returns to origin and clears flag")
  void resolveWithoutTypeChangeReturnsToOriginAndClearsFlag(ApprovalCase testCase) {
    Fixture fx = newFixture(testCase, ReextractionStatus.NONE);

    WorkflowOutcome flagOutcome =
        fx.engine.applyAction(fx.documentId, new WorkflowAction.Flag("needs receipt"));
    assertThat(flagOutcome).isInstanceOf(WorkflowOutcome.Success.class);
    WorkflowInstance flagged = ((WorkflowOutcome.Success) flagOutcome).instance();
    assertThat(flagged.currentStageId()).isEqualTo(fx.reviewStageId);
    assertThat(flagged.workflowOriginStage()).isEqualTo(testCase.approvalStageId());
    assertThat(flagged.flagComment()).isEqualTo("needs receipt");
    assertThat(flagged.currentStatus()).isEqualTo(WorkflowStatus.FLAGGED);

    WorkflowOutcome resolveOutcome =
        fx.engine.applyAction(fx.documentId, new WorkflowAction.Resolve(null));
    assertThat(resolveOutcome).isInstanceOf(WorkflowOutcome.Success.class);
    WorkflowInstance resolved = ((WorkflowOutcome.Success) resolveOutcome).instance();
    assertThat(resolved.currentStageId()).isEqualTo(testCase.approvalStageId());
    assertThat(resolved.workflowOriginStage()).isNull();
    assertThat(resolved.flagComment()).isNull();
    assertThat(resolved.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_APPROVAL);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("approvalStages")
  @DisplayName(
      "Resolve with type change stays in Review and clears origin after ExtractionCompleted")
  void resolveWithTypeChangeStaysInReviewAndClearsOriginAfterExtractionCompleted(
      ApprovalCase testCase) {
    Fixture fx = newFixture(testCase, ReextractionStatus.NONE);

    WorkflowOutcome flagOutcome =
        fx.engine.applyAction(fx.documentId, new WorkflowAction.Flag("retype me"));
    assertThat(flagOutcome).isInstanceOf(WorkflowOutcome.Success.class);

    fx.eventBus.clear();

    String newDocType = pickRetypeTarget(testCase);
    WorkflowOutcome resolveOutcome =
        fx.engine.applyAction(fx.documentId, new WorkflowAction.Resolve(newDocType));
    assertThat(resolveOutcome).isInstanceOf(WorkflowOutcome.Success.class);

    WorkflowInstance midFlight = fx.currentInstance();
    assertThat(midFlight.currentStageId()).isEqualTo(fx.reviewStageId);
    assertThat(midFlight.workflowOriginStage()).isEqualTo(testCase.approvalStageId());

    fx.simulateExtractionCompleted(newDocType);

    WorkflowInstance afterClear = fx.currentInstance();
    assertThat(afterClear.currentStageId()).isEqualTo(fx.reviewStageId);
    assertThat(afterClear.workflowOriginStage()).isNull();
    assertThat(afterClear.flagComment()).isNull();
    assertThat(afterClear.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_REVIEW);

    List<DocumentStateChanged> retypeEvents = fx.eventBus.events();
    assertThat(retypeEvents)
        .as("retype path emits exactly two DocumentStateChanged events")
        .hasSize(2);
    assertThat(retypeEvents.get(0).reextractionStatus())
        .isEqualTo(ReextractionStatus.IN_PROGRESS.name());
    assertThat(retypeEvents.get(0).action()).isEqualTo("RESOLVE");
    assertThat(retypeEvents.get(1).reextractionStatus()).isEqualTo(ReextractionStatus.NONE.name());
    assertThat(retypeEvents.get(1).action()).isNull();
  }

  private static String pickRetypeTarget(ApprovalCase testCase) {
    String current = testCase.documentTypeId();
    return current.equals(NEW_DOC_TYPE_FOR_RETYPE) ? SUBSTITUTE_DOC_TYPE : NEW_DOC_TYPE_FOR_RETYPE;
  }

  private Fixture newFixture(ApprovalCase testCase, ReextractionStatus reextractionStatus) {
    WorkflowView view = testCase.augmentedView();
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
            view.organizationId(),
            view.documentTypeId(),
            testCase.guardSatisfyingFields(),
            "raw text",
            FIXED_NOW,
            reextractionStatus);
    store.putDocument(document);

    StageView startStage = testCase.approvalStage();
    WorkflowInstance instance =
        new WorkflowInstance(
            UUID.randomUUID(),
            documentId,
            view.organizationId(),
            startStage.id(),
            store.deriveStatus(startStage, null),
            null,
            null,
            FIXED_NOW);
    store.putInstance(instance);

    LlmExtractor llmExtractor = mock(LlmExtractor.class);
    RecordingEventBus eventBus = new RecordingEventBus();
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
    return new Fixture(engine, store, documentId, view, eventBus);
  }

  private static List<WorkflowDoc> loadSeed() {
    ObjectMapper yaml = YAMLMapper.builder().build();
    List<OrganizationDoc> organizations =
        readResource(yaml, SEED_ROOT + "organizations.yaml", new TypeReference<>() {});
    List<WorkflowDoc> docs = new ArrayList<>();
    for (OrganizationDoc org : organizations) {
      for (String docTypeId : org.documentTypeIds()) {
        String workflowPath = SEED_ROOT + "workflows/" + org.id() + "/" + docTypeId + ".yaml";
        WorkflowYaml workflow = readResource(yaml, workflowPath, new TypeReference<>() {});
        docs.add(new WorkflowDoc(org.id(), docTypeId, workflow));
      }
    }
    return docs;
  }

  private static <T> T readResource(ObjectMapper yaml, String resourcePath, TypeReference<T> type) {
    try (InputStream in =
        FlagOriginRestorationTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("seed resource not found: " + resourcePath);
      }
      return yaml.readValue(in, type);
    } catch (IOException e) {
      throw new IllegalStateException("failed to read " + resourcePath, e);
    }
  }

  private static Stream<ApprovalCase> expandApprovalCases(WorkflowDoc doc) {
    List<StageView> stages = toStageViews(doc.workflow().stages());
    List<TransitionView> transitions = toTransitionViews(doc.workflow().transitions());
    StageView reviewStage = findReviewStage(stages, doc);
    Map<String, Object> guardFields = guardSatisfyingFieldsFor(doc.workflow().transitions());

    List<TransitionView> augmented =
        augmentWithFlagTransitions(transitions, stages, reviewStage.id());

    return stages.stream()
        .filter(s -> STAGE_KIND_APPROVAL.equals(s.kind().toLowerCase(Locale.ROOT)))
        .map(
            approval ->
                new ApprovalCase(
                    doc.organizationId(),
                    doc.documentTypeId(),
                    approval,
                    new WorkflowView(doc.organizationId(), doc.documentTypeId(), stages, augmented),
                    reviewStage.id(),
                    guardFields));
  }

  private static StageView findReviewStage(List<StageView> stages, WorkflowDoc doc) {
    for (StageView stage : stages) {
      if (STAGE_KIND_REVIEW.equals(stage.kind().toLowerCase(Locale.ROOT))) {
        return stage;
      }
    }
    throw new IllegalStateException(
        "no review stage in workflow for " + doc.organizationId() + "/" + doc.documentTypeId());
  }

  private static List<StageView> toStageViews(List<StageYaml> stages) {
    List<StageView> out = new ArrayList<>(stages.size());
    for (StageYaml s : stages) {
      out.add(new StageView(s.id(), s.displayName(), s.kind(), s.canonicalStatus(), s.role()));
    }
    return out;
  }

  private static List<TransitionView> toTransitionViews(List<TransitionYaml> transitions) {
    List<TransitionView> out = new ArrayList<>(transitions.size());
    for (TransitionYaml t : transitions) {
      GuardView guard =
          t.guard() == null
              ? null
              : new GuardView(t.guard().field(), t.guard().op(), t.guard().value());
      out.add(new TransitionView(t.from(), t.to(), t.action(), guard));
    }
    return out;
  }

  private static List<TransitionView> augmentWithFlagTransitions(
      List<TransitionView> transitions, List<StageView> stages, String reviewStageId) {
    List<TransitionView> out = new ArrayList<>(transitions);
    for (StageView stage : stages) {
      if (STAGE_KIND_APPROVAL.equals(stage.kind().toLowerCase(Locale.ROOT))) {
        out.add(new TransitionView(stage.id(), reviewStageId, FLAG_ACTION, null));
      }
    }
    return List.copyOf(out);
  }

  private static Map<String, Object> guardSatisfyingFieldsFor(List<TransitionYaml> transitions) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (TransitionYaml t : transitions) {
      if (t.guard() == null) {
        continue;
      }
      String field = t.guard().field();
      String value = t.guard().value();
      String op = t.guard().op();
      if ("EQ".equals(op) && !fields.containsKey(field)) {
        fields.put(field, value);
      }
    }
    return Map.copyOf(fields);
  }

  private static final class Fixture {
    private final WorkflowEngine engine;
    private final InMemoryStore store;
    private final UUID documentId;
    private final RecordingEventBus eventBus;
    private final String reviewStageId;

    Fixture(
        WorkflowEngine engine,
        InMemoryStore store,
        UUID documentId,
        WorkflowView view,
        RecordingEventBus eventBus) {
      this.engine = engine;
      this.store = store;
      this.documentId = documentId;
      this.eventBus = eventBus;
      this.reviewStageId = findReviewId(view);
    }

    WorkflowInstance currentInstance() {
      return store.instance(documentId);
    }

    void simulateExtractionCompleted(String newDocTypeId) {
      Document doc = store.document(documentId);
      Document updated =
          new Document(
              doc.id(),
              doc.storedDocumentId(),
              doc.organizationId(),
              newDocTypeId,
              doc.extractedFields(),
              doc.rawText(),
              doc.processedAt(),
              ReextractionStatus.NONE);
      store.putDocument(updated);

      WorkflowInstance prior = store.instance(documentId);
      StageView review = store.reviewStage();
      WorkflowInstance cleared =
          new WorkflowInstance(
              prior.id(),
              prior.documentId(),
              prior.organizationId(),
              review.id(),
              store.deriveStatus(review, null),
              null,
              null,
              FIXED_NOW);
      store.putInstance(cleared);

      eventBus.publish(
          new DocumentStateChanged(
              updated.id(),
              updated.storedDocumentId(),
              updated.organizationId(),
              cleared.currentStageId(),
              cleared.currentStatus().name(),
              ReextractionStatus.NONE.name(),
              null,
              null,
              FIXED_NOW));
    }

    private static String findReviewId(WorkflowView view) {
      for (StageView stage : view.stages()) {
        if (STAGE_KIND_REVIEW.equals(stage.kind().toLowerCase(Locale.ROOT))) {
          return stage.id();
        }
      }
      throw new IllegalStateException("no review stage");
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
    private final List<DocumentStateChanged> events = new CopyOnWriteArrayList<>();

    RecordingEventBus() {
      super(mock(ApplicationEventPublisher.class));
    }

    @Override
    public void publish(DocumentEvent event) {
      if (event instanceof DocumentStateChanged dsc) {
        events.add(dsc);
      }
    }

    List<DocumentStateChanged> events() {
      return List.copyOf(events);
    }

    void clear() {
      events.clear();
    }
  }

  record ApprovalCase(
      String organizationId,
      String documentTypeId,
      StageView approvalStage,
      WorkflowView augmentedView,
      String reviewStageId,
      Map<String, Object> guardSatisfyingFields) {

    String approvalStageId() {
      return approvalStage.id();
    }

    @Override
    public String toString() {
      return organizationId + "/" + documentTypeId + " @ " + approvalStage.id();
    }
  }

  private record WorkflowDoc(String organizationId, String documentTypeId, WorkflowYaml workflow) {}

  record OrganizationDoc(
      String id, String displayName, String iconId, List<String> documentTypeIds) {}

  record WorkflowYaml(
      String organizationId,
      String documentTypeId,
      List<StageYaml> stages,
      List<TransitionYaml> transitions) {}

  record StageYaml(
      String id, String displayName, String kind, String canonicalStatus, String role) {}

  record TransitionYaml(String from, String to, String action, GuardYaml guard) {}

  record GuardYaml(String field, String op, String value) {}
}
