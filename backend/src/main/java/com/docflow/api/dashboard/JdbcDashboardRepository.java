package com.docflow.api.dashboard;

import com.docflow.api.dto.DashboardStats;
import com.docflow.api.dto.DocumentView;
import com.docflow.api.dto.ProcessingItem;
import com.docflow.document.ReextractionStatus;
import com.docflow.workflow.WorkflowStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
@Primary
class JdbcDashboardRepository implements DashboardRepository {

  private static final TypeReference<Map<String, Object>> FIELD_MAP_TYPE = new TypeReference<>() {};

  private static final String LIST_PROCESSING_SQL =
      "SELECT pd.id AS processing_id, pd.stored_document_id, sd.source_filename, "
          + "pd.current_step, pd.last_error, pd.created_at "
          + "FROM processing_documents pd "
          + "JOIN stored_documents sd ON sd.id = pd.stored_document_id "
          + "LEFT JOIN documents d ON d.stored_document_id = pd.stored_document_id "
          + "WHERE pd.organization_id = :orgId AND d.id IS NULL "
          + "ORDER BY pd.created_at DESC";

  private static final String LIST_DOCUMENTS_SQL =
      "SELECT d.id AS document_id, d.organization_id, sd.source_filename, sd.mime_type, "
          + "sd.uploaded_at, d.processed_at, "
          + "wi.current_stage_id, s.display_name AS stage_display_name, wi.current_status, "
          + "wi.workflow_origin_stage, wi.flag_comment, "
          + "d.detected_document_type, d.extracted_fields, d.reextraction_status, "
          + "wi.updated_at "
          + "FROM documents d "
          + "JOIN stored_documents sd ON sd.id = d.stored_document_id "
          + "JOIN workflow_instances wi ON wi.document_id = d.id "
          + "LEFT JOIN stages s "
          + "  ON s.organization_id = wi.organization_id "
          + "  AND s.document_type_id = wi.document_type_id "
          + "  AND s.id = wi.current_stage_id "
          + "WHERE d.organization_id = :orgId "
          + "  AND (CAST(:status AS VARCHAR) IS NULL OR wi.current_status = :status) "
          + "  AND (CAST(:stage AS VARCHAR) IS NULL OR s.display_name = :stage) "
          + "  AND (CAST(:docType AS VARCHAR) IS NULL OR d.detected_document_type = :docType) "
          + "ORDER BY wi.updated_at DESC "
          + "LIMIT 200";

  private static final String STATS_AGGREGATE_SQL =
      "SELECT "
          + "COUNT(*) FILTER (WHERE current_status NOT IN ('FILED','REJECTED')) "
          + "  AS in_progress, "
          + "COUNT(*) FILTER (WHERE current_status = 'AWAITING_REVIEW') AS awaiting_review, "
          + "COUNT(*) FILTER (WHERE current_status = 'FLAGGED') AS flagged, "
          + "COUNT(*) FILTER (WHERE current_status = 'FILED' AND updated_at >= :monthStart) "
          + "  AS filed_this_month "
          + "FROM workflow_instances "
          + "WHERE organization_id = :orgId";

  private final NamedParameterJdbcOperations jdbc;
  private final ObjectMapper jsonMapper;
  private final Clock clock;

  JdbcDashboardRepository(NamedParameterJdbcOperations jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.jsonMapper = JsonMapper.builder().build();
  }

  @Override
  public List<ProcessingItem> listProcessing(String orgId) {
    return jdbc.query(LIST_PROCESSING_SQL, Map.of("orgId", orgId), processingRowMapper());
  }

  @Override
  public List<DocumentView> listDocuments(
      String orgId,
      Optional<WorkflowStatus> statusFilter,
      Optional<String> stageDisplayNameFilter,
      Optional<String> docTypeFilter) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("orgId", orgId)
            .addValue("status", statusFilter.map(Enum::name).orElse(null))
            .addValue("stage", stageDisplayNameFilter.orElse(null))
            .addValue("docType", docTypeFilter.orElse(null));
    return jdbc.query(LIST_DOCUMENTS_SQL, params, documentRowMapper());
  }

  @Override
  public DashboardStats stats(String orgId) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("orgId", orgId)
            .addValue("monthStart", Timestamp.from(currentMonthStart()));
    return jdbc.queryForObject(STATS_AGGREGATE_SQL, params, statsRowMapper());
  }

  private static RowMapper<DashboardStats> statsRowMapper() {
    return (ResultSet rs, int rowNum) ->
        new DashboardStats(
            rs.getLong("in_progress"),
            rs.getLong("awaiting_review"),
            rs.getLong("flagged"),
            rs.getLong("filed_this_month"));
  }

  private Instant currentMonthStart() {
    Instant now = clock.instant();
    YearMonth ym = YearMonth.from(now.atOffset(ZoneOffset.UTC));
    return ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
  }

  private static RowMapper<ProcessingItem> processingRowMapper() {
    return (ResultSet rs, int rowNum) -> {
      Timestamp createdAt = rs.getTimestamp("created_at");
      return new ProcessingItem(
          (UUID) rs.getObject("processing_id"),
          (UUID) rs.getObject("stored_document_id"),
          rs.getString("source_filename"),
          rs.getString("current_step"),
          rs.getString("last_error"),
          createdAt == null ? null : createdAt.toInstant());
    };
  }

  private RowMapper<DocumentView> documentRowMapper() {
    return (ResultSet rs, int rowNum) -> {
      Object extractedFieldsRaw = rs.getObject("extracted_fields");
      Map<String, Object> fields =
          extractedFieldsRaw == null
              ? Map.of()
              : jsonMapper.readValue(extractedFieldsRaw.toString(), FIELD_MAP_TYPE);
      Timestamp uploadedAt = rs.getTimestamp("uploaded_at");
      Timestamp processedAt = rs.getTimestamp("processed_at");
      return new DocumentView(
          (UUID) rs.getObject("document_id"),
          rs.getString("organization_id"),
          rs.getString("source_filename"),
          rs.getString("mime_type"),
          uploadedAt == null ? null : uploadedAt.toInstant(),
          processedAt == null ? null : processedAt.toInstant(),
          null,
          rs.getString("current_stage_id"),
          rs.getString("stage_display_name"),
          parseStatus(rs),
          rs.getString("workflow_origin_stage"),
          rs.getString("flag_comment"),
          rs.getString("detected_document_type"),
          fields,
          parseReextractionStatus(rs));
    };
  }

  private static WorkflowStatus parseStatus(ResultSet rs) throws SQLException {
    String value = rs.getString("current_status");
    return value == null ? null : WorkflowStatus.valueOf(value);
  }

  private static ReextractionStatus parseReextractionStatus(ResultSet rs) throws SQLException {
    String value = rs.getString("reextraction_status");
    return value == null ? ReextractionStatus.NONE : ReextractionStatus.valueOf(value);
  }
}
