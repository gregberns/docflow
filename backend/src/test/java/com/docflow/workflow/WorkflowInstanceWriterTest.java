package com.docflow.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.workflow.internal.JdbcWorkflowInstanceWriter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

class WorkflowInstanceWriterTest {

  private static final String ORG_ID = "test-org";
  private static final String DOC_TYPE_ID = "test-doc-type";

  private static final String STAGE_REVIEW_ID = "stage-rv";
  private static final String STAGE_MGR_ID = "stage-mgr";
  private static final String STAGE_FILED_ID = "stage-fl";

  private static final StageView REVIEW_STAGE =
      new StageView(STAGE_REVIEW_ID, "rv-display", "review", "AWAITING_REVIEW", null);
  private static final StageView MANAGER_STAGE =
      new StageView(STAGE_MGR_ID, "mgr-display", "approval", "AWAITING_APPROVAL", "manager");
  private static final StageView FILED_STAGE =
      new StageView(STAGE_FILED_ID, "fl-display", "terminal", "FILED", null);

  private static final Instant FIXED_NOW = Instant.parse("2026-04-28T12:00:00Z");
  private static final Instant PRIOR_AT = Instant.parse("2026-04-28T11:00:00Z");
  private static final Instant REFRESHED_AT = Instant.parse("2026-04-28T11:30:00Z");

  private NamedParameterJdbcOperations jdbc;
  private WorkflowCatalog catalog;
  private JdbcWorkflowInstanceWriter writer;
  private UUID documentId;

  @BeforeEach
  void setUp() {
    jdbc = Mockito.mock(NamedParameterJdbcOperations.class);
    catalog = Mockito.mock(WorkflowCatalog.class);
    Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    writer = new JdbcWorkflowInstanceWriter(jdbc, clock);
    documentId = UUID.randomUUID();

    WorkflowView view =
        new WorkflowView(
            ORG_ID, DOC_TYPE_ID, List.of(REVIEW_STAGE, MANAGER_STAGE, FILED_STAGE), List.of());
    when(catalog.getWorkflow(ORG_ID, DOC_TYPE_ID)).thenReturn(Optional.of(view));
  }

  @Test
  void advanceStage_toApproval_setsStageAndCanonicalStatusInOneUpdate() {
    seedSelect(STAGE_REVIEW_ID, null, null);
    seedUpdateSucceeds();

    writer.advanceStage(documentId, STAGE_MGR_ID, catalog, ORG_ID, DOC_TYPE_ID);

    Map<String, Object> params = captureUpdateParams();
    assertThat(params).containsEntry("currentStageId", STAGE_MGR_ID);
    assertThat(params).containsEntry("currentStatus", WorkflowStatus.AWAITING_APPROVAL.name());
    assertThat(params).containsEntry("workflowOriginStage", null);
    assertThat(params).containsEntry("flagComment", null);
    assertThat(params).containsEntry("documentId", documentId);
    assertThat(params).containsEntry("priorUpdatedAt", Timestamp.from(PRIOR_AT));
    assertThat(params).containsEntry("newUpdatedAt", Timestamp.from(FIXED_NOW));
    verify(jdbc, times(1))
        .update(eq(JdbcWorkflowInstanceWriter.UPDATE_SQL), any(MapSqlParameterSource.class));
  }

  @Test
  void advanceStage_toReviewWithExistingOrigin_yieldsFlagged() {
    seedSelect(STAGE_MGR_ID, STAGE_MGR_ID, "needs receipt");
    seedUpdateSucceeds();

    writer.advanceStage(documentId, STAGE_REVIEW_ID, catalog, ORG_ID, DOC_TYPE_ID);

    Map<String, Object> params = captureUpdateParams();
    assertThat(params).containsEntry("currentStageId", STAGE_REVIEW_ID);
    assertThat(params).containsEntry("currentStatus", WorkflowStatus.FLAGGED.name());
    assertThat(params).containsEntry("workflowOriginStage", STAGE_MGR_ID);
    assertThat(params).containsEntry("flagComment", "needs receipt");
  }

  @Test
  void setFlag_fromApproval_movesToReviewSetsOriginAndCommentAndFlagged() {
    seedSelect(STAGE_MGR_ID, null, null);
    seedUpdateSucceeds();

    writer.setFlag(documentId, STAGE_MGR_ID, "needs receipt", catalog, ORG_ID, DOC_TYPE_ID);

    Map<String, Object> params = captureUpdateParams();
    assertThat(params).containsEntry("currentStageId", STAGE_REVIEW_ID);
    assertThat(params).containsEntry("currentStatus", WorkflowStatus.FLAGGED.name());
    assertThat(params).containsEntry("workflowOriginStage", STAGE_MGR_ID);
    assertThat(params).containsEntry("flagComment", "needs receipt");
  }

  @Test
  void clearFlag_withOriginSet_movesToOriginAndDerivesFromOriginCanonicalStatus() {
    seedSelect(STAGE_REVIEW_ID, STAGE_MGR_ID, "earlier comment");
    seedUpdateSucceeds();

    writer.clearFlag(documentId, catalog, ORG_ID, DOC_TYPE_ID);

    Map<String, Object> params = captureUpdateParams();
    assertThat(params).containsEntry("currentStageId", STAGE_MGR_ID);
    assertThat(params).containsEntry("currentStatus", WorkflowStatus.AWAITING_APPROVAL.name());
    assertThat(params).containsEntry("workflowOriginStage", null);
    assertThat(params).containsEntry("flagComment", null);
  }

  @Test
  void clearFlag_fromReviewWithNoOrigin_leavesStageButClearsCommentField() {
    seedSelect(STAGE_REVIEW_ID, null, null);
    seedUpdateSucceeds();

    writer.clearFlag(documentId, catalog, ORG_ID, DOC_TYPE_ID);

    Map<String, Object> params = captureUpdateParams();
    assertThat(params).containsEntry("currentStageId", STAGE_REVIEW_ID);
    assertThat(params).containsEntry("currentStatus", WorkflowStatus.AWAITING_REVIEW.name());
    assertThat(params).containsEntry("workflowOriginStage", null);
    assertThat(params).containsEntry("flagComment", null);
    verify(jdbc, times(1))
        .update(eq(JdbcWorkflowInstanceWriter.UPDATE_SQL), any(MapSqlParameterSource.class));
  }

  @Test
  void optimisticLock_retriesOnceAndSucceedsOnSecondAttempt() {
    seedSelectSequence(
        new Row(STAGE_REVIEW_ID, null, null, PRIOR_AT),
        new Row(STAGE_REVIEW_ID, null, null, REFRESHED_AT));
    when(jdbc.update(eq(JdbcWorkflowInstanceWriter.UPDATE_SQL), any(MapSqlParameterSource.class)))
        .thenReturn(0)
        .thenReturn(1);

    writer.advanceStage(documentId, STAGE_MGR_ID, catalog, ORG_ID, DOC_TYPE_ID);

    ArgumentCaptor<MapSqlParameterSource> captor =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(jdbc, times(2)).update(eq(JdbcWorkflowInstanceWriter.UPDATE_SQL), captor.capture());
    List<MapSqlParameterSource> all = captor.getAllValues();
    assertThat(toMap(all.get(0))).containsEntry("priorUpdatedAt", Timestamp.from(PRIOR_AT));
    assertThat(toMap(all.get(1))).containsEntry("priorUpdatedAt", Timestamp.from(REFRESHED_AT));
  }

  @Test
  void optimisticLock_throwsAfterTwoConsecutiveZeroRowUpdates() {
    seedSelectSequence(
        new Row(STAGE_REVIEW_ID, null, null, PRIOR_AT),
        new Row(STAGE_REVIEW_ID, null, null, REFRESHED_AT));
    when(jdbc.update(eq(JdbcWorkflowInstanceWriter.UPDATE_SQL), any(MapSqlParameterSource.class)))
        .thenReturn(0);

    assertThatThrownBy(
            () -> writer.advanceStage(documentId, STAGE_MGR_ID, catalog, ORG_ID, DOC_TYPE_ID))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  private void seedSelect(String stageId, String origin, String comment) {
    seedSelectSequence(new Row(stageId, origin, comment, PRIOR_AT));
  }

  private void seedSelectSequence(Row first, Row... rest) {
    Map<String, Object> firstMap = first.toRow();
    @SuppressWarnings("unchecked")
    Map<String, Object>[] more = new Map[rest.length];
    for (int i = 0; i < rest.length; i++) {
      more[i] = rest[i].toRow();
    }
    when(jdbc.queryForMap(anyString(), anyMap())).thenReturn(firstMap, more);
  }

  private void seedUpdateSucceeds() {
    when(jdbc.update(eq(JdbcWorkflowInstanceWriter.UPDATE_SQL), any(MapSqlParameterSource.class)))
        .thenReturn(1);
  }

  private Map<String, Object> captureUpdateParams() {
    ArgumentCaptor<MapSqlParameterSource> captor =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    verify(jdbc).update(eq(JdbcWorkflowInstanceWriter.UPDATE_SQL), captor.capture());
    return toMap(captor.getValue());
  }

  private static Map<String, Object> toMap(MapSqlParameterSource params) {
    Map<String, Object> out = new HashMap<>();
    for (String name : params.getParameterNames()) {
      out.put(name, params.getValue(name));
    }
    return out;
  }

  private record Row(String stageId, String origin, String comment, Instant updatedAt) {
    Map<String, Object> toRow() {
      Map<String, Object> row = new HashMap<>();
      row.put("current_stage_id", stageId);
      row.put("workflow_origin_stage", origin);
      row.put("flag_comment", comment);
      row.put("updated_at", Timestamp.from(updatedAt));
      return row;
    }
  }
}
