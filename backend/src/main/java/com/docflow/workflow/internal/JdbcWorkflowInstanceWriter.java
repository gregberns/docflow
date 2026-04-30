package com.docflow.workflow.internal;

import com.docflow.api.error.UnknownDocumentException;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.workflow.StaleWorkflowStateException;
import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceWriter;
import com.docflow.workflow.WorkflowStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Component;

@Component
public class JdbcWorkflowInstanceWriter implements WorkflowInstanceWriter {

  public static final String UPDATE_SQL =
      "UPDATE workflow_instances SET "
          + "current_stage_id = :currentStageId, "
          + "current_status = :currentStatus, "
          + "workflow_origin_stage = :workflowOriginStage, "
          + "flag_comment = :flagComment, "
          + "updated_at = :newUpdatedAt "
          + "WHERE document_id = :documentId "
          + "AND updated_at = :priorUpdatedAt "
          + "AND current_stage_id = :priorStageId";

  public static final String CLEAR_ORIGIN_KEEP_STAGE_SQL =
      "UPDATE workflow_instances SET "
          + "document_type_id = :newDocTypeId, "
          + "current_stage_id = :newStageId, "
          + "current_status = :currentStatus, "
          + "workflow_origin_stage = NULL, "
          + "flag_comment = NULL, "
          + "updated_at = :newUpdatedAt "
          + "WHERE document_id = :documentId";

  public static final String INSERT_SQL =
      "INSERT INTO workflow_instances "
          + "(id, document_id, organization_id, document_type_id, current_stage_id, "
          + "current_status, workflow_origin_stage, flag_comment, updated_at) "
          + "VALUES (:id, :documentId, :organizationId, :documentTypeId, :currentStageId, "
          + ":currentStatus, :workflowOriginStage, :flagComment, :updatedAt)";

  private static final String SELECT_STATE_SQL =
      "SELECT current_stage_id, workflow_origin_stage, updated_at "
          + "FROM workflow_instances WHERE document_id = :documentId";

  private static final String COUNT_BY_DOCUMENT_SQL =
      "SELECT 1 FROM workflow_instances WHERE document_id = :documentId LIMIT 1";

  private static final String STAGE_KIND_REVIEW = "review";

  private final NamedParameterJdbcOperations jdbc;
  private final Clock clock;

  public JdbcWorkflowInstanceWriter(NamedParameterJdbcOperations jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public void insert(WorkflowInstance instance, String documentTypeId) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("id", instance.id())
            .addValue("documentId", instance.documentId())
            .addValue("organizationId", instance.organizationId())
            .addValue("documentTypeId", documentTypeId)
            .addValue("currentStageId", instance.currentStageId())
            .addValue("currentStatus", instance.currentStatus().name())
            .addValue("workflowOriginStage", instance.workflowOriginStage())
            .addValue("flagComment", instance.flagComment())
            .addValue("updatedAt", Timestamp.from(instance.updatedAt()));
    jdbc.update(INSERT_SQL, params);
  }

  @Override
  public void advanceStage(
      UUID documentId, String newStageId, WorkflowCatalog catalog, String orgId, String docTypeId) {
    StageView target = requireStage(catalog, orgId, docTypeId, newStageId);
    State prior = readState(documentId);
    WorkflowStatus status = deriveStatus(target, prior.workflowOriginStage());
    String newOrigin = isReview(target) ? prior.workflowOriginStage() : null;
    String newComment = isReview(target) ? prior.flagComment() : null;
    writeGuarded(documentId, newStageId, status, newOrigin, newComment, prior);
  }

  @Override
  public void setFlag(
      UUID documentId,
      String originStageId,
      String comment,
      WorkflowCatalog catalog,
      String orgId,
      String docTypeId) {
    State prior = readState(documentId);
    StageView reviewStage = findReviewStage(catalog, orgId, docTypeId);
    WorkflowStatus status = deriveStatus(reviewStage, originStageId);
    writeGuarded(documentId, reviewStage.id(), status, originStageId, comment, prior);
  }

  @Override
  public void clearFlag(UUID documentId, WorkflowCatalog catalog, String orgId, String docTypeId) {
    State prior = readState(documentId);
    StageView currentStage = requireStage(catalog, orgId, docTypeId, prior.currentStageId());
    String targetStageId;
    if (isReview(currentStage) && prior.workflowOriginStage() == null) {
      targetStageId = prior.currentStageId();
    } else if (prior.workflowOriginStage() != null) {
      targetStageId = prior.workflowOriginStage();
    } else {
      targetStageId = prior.currentStageId();
    }
    StageView target = requireStage(catalog, orgId, docTypeId, targetStageId);
    WorkflowStatus status = deriveStatus(target, null);
    writeGuarded(documentId, targetStageId, status, null, null, prior);
  }

  @Override
  public void clearOriginKeepStage(
      UUID documentId, WorkflowCatalog catalog, String orgId, String newDocTypeId) {
    StageView reviewStage = findReviewStage(catalog, orgId, newDocTypeId);
    WorkflowStatus status = WorkflowStatus.valueOf(reviewStage.canonicalStatus());
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("documentId", documentId)
            .addValue("newDocTypeId", newDocTypeId)
            .addValue("newStageId", reviewStage.id())
            .addValue("currentStatus", status.name())
            .addValue("newUpdatedAt", Timestamp.from(Instant.now(clock)));
    jdbc.update(CLEAR_ORIGIN_KEEP_STAGE_SQL, params);
  }

  private void writeGuarded(
      UUID documentId,
      String newStageId,
      WorkflowStatus status,
      String originStage,
      String comment,
      State prior) {
    Instant newUpdatedAt = Instant.now(clock);
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("documentId", documentId)
            .addValue("currentStageId", newStageId)
            .addValue("currentStatus", status.name())
            .addValue("workflowOriginStage", originStage)
            .addValue("flagComment", comment)
            .addValue("newUpdatedAt", Timestamp.from(newUpdatedAt))
            .addValue("priorUpdatedAt", Timestamp.from(prior.updatedAt()))
            .addValue("priorStageId", prior.currentStageId());
    int rows = jdbc.update(UPDATE_SQL, params);
    if (rows != 1) {
      List<Integer> existing =
          jdbc.queryForList(COUNT_BY_DOCUMENT_SQL, Map.of("documentId", documentId), Integer.class);
      if (existing.isEmpty()) {
        throw new UnknownDocumentException(documentId.toString());
      }
      throw new StaleWorkflowStateException(documentId, prior.currentStageId());
    }
  }

  private State readState(UUID documentId) {
    Map<String, Object> row = jdbc.queryForMap(SELECT_STATE_SQL, Map.of("documentId", documentId));
    String stageId = (String) row.get("current_stage_id");
    String origin = (String) row.get("workflow_origin_stage");
    String comment = (String) row.get("flag_comment");
    Object rawUpdatedAt = row.get("updated_at");
    Instant updatedAt = toInstant(rawUpdatedAt);
    return new State(stageId, origin, comment, updatedAt);
  }

  private static Instant toInstant(Object value) {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof Timestamp ts) {
      return ts.toInstant();
    }
    if (value instanceof java.time.OffsetDateTime odt) {
      return odt.toInstant();
    }
    throw new IllegalStateException("unsupported updated_at type: " + value);
  }

  private static StageView requireStage(
      WorkflowCatalog catalog, String orgId, String docTypeId, String stageId) {
    WorkflowView workflow =
        catalog
            .getWorkflow(orgId, docTypeId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no workflow for org=" + orgId + " docType=" + docTypeId));
    for (StageView stage : workflow.stages()) {
      if (stage.id().equals(stageId)) {
        return stage;
      }
    }
    throw new IllegalStateException(
        "stage " + stageId + " not in workflow for org=" + orgId + " docType=" + docTypeId);
  }

  private static StageView findReviewStage(
      WorkflowCatalog catalog, String orgId, String docTypeId) {
    WorkflowView workflow =
        catalog
            .getWorkflow(orgId, docTypeId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no workflow for org=" + orgId + " docType=" + docTypeId));
    for (StageView stage : workflow.stages()) {
      if (isReview(stage)) {
        return stage;
      }
    }
    throw new IllegalStateException(
        "no review stage in workflow for org=" + orgId + " docType=" + docTypeId);
  }

  private static WorkflowStatus deriveStatus(StageView stage, String originStage) {
    if (isReview(stage) && originStage != null) {
      return WorkflowStatus.FLAGGED;
    }
    return WorkflowStatus.valueOf(stage.canonicalStatus());
  }

  private static boolean isReview(StageView stage) {
    return STAGE_KIND_REVIEW.equals(stage.kind().toLowerCase(Locale.ROOT));
  }

  private record State(
      String currentStageId, String workflowOriginStage, String flagComment, Instant updatedAt) {}
}
