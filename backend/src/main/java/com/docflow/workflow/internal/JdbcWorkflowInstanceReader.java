package com.docflow.workflow.internal;

import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceReader;
import com.docflow.workflow.WorkflowStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Component;

@Component
public class JdbcWorkflowInstanceReader implements WorkflowInstanceReader {

  private static final String SELECT_SQL =
      "SELECT id, document_id, organization_id, current_stage_id, current_status, "
          + "workflow_origin_stage, flag_comment, updated_at "
          + "FROM workflow_instances WHERE document_id = :documentId";

  private final NamedParameterJdbcOperations jdbc;

  public JdbcWorkflowInstanceReader(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<WorkflowInstance> getByDocumentId(UUID documentId) {
    try {
      WorkflowInstance instance =
          jdbc.queryForObject(SELECT_SQL, Map.of("documentId", documentId), rowMapper());
      return Optional.ofNullable(instance);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private RowMapper<WorkflowInstance> rowMapper() {
    return (ResultSet rs, int rowNum) -> {
      Timestamp updatedAt = rs.getTimestamp("updated_at");
      Instant updatedAtInstant = updatedAt == null ? null : updatedAt.toInstant();
      return new WorkflowInstance(
          (UUID) rs.getObject("id"),
          (UUID) rs.getObject("document_id"),
          rs.getString("organization_id"),
          rs.getString("current_stage_id"),
          parseStatus(rs),
          rs.getString("workflow_origin_stage"),
          rs.getString("flag_comment"),
          updatedAtInstant);
    };
  }

  private static WorkflowStatus parseStatus(ResultSet rs) throws SQLException {
    String value = rs.getString("current_status");
    return value == null ? null : WorkflowStatus.valueOf(value);
  }
}
