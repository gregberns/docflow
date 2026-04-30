package com.docflow.document.internal;

import com.docflow.document.Document;
import com.docflow.document.DocumentWriter;
import com.docflow.document.ReextractionStatus;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JdbcDocumentWriter implements DocumentWriter {

  private static final String INSERT_SQL =
      "INSERT INTO documents "
          + "(id, stored_document_id, organization_id, detected_document_type, "
          + "extracted_fields, raw_text, processed_at, reextraction_status) "
          + "VALUES (:id, :storedDocumentId, :organizationId, :detectedDocumentType, "
          + "CAST(:extractedFields AS jsonb), :rawText, :processedAt, :reextractionStatus)";

  private static final String UPDATE_EXTRACTION_SQL =
      "UPDATE documents SET "
          + "detected_document_type = :detectedDocumentType, "
          + "extracted_fields = CAST(:extractedFields AS jsonb) "
          + "WHERE id = :id";

  private static final String UPDATE_REEXTRACTION_STATUS_SQL =
      "UPDATE documents SET reextraction_status = :reextractionStatus WHERE id = :id";

  private static final String CLAIM_REEXTRACTION_IN_PROGRESS_SQL =
      "UPDATE documents SET reextraction_status = 'IN_PROGRESS' "
          + "WHERE id = :id AND reextraction_status != 'IN_PROGRESS'";

  private final NamedParameterJdbcOperations jdbc;
  private final ObjectMapper jsonMapper;

  public JdbcDocumentWriter(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
    this.jsonMapper = JsonMapper.builder().build();
  }

  @Override
  public void insert(Document document) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("id", document.id())
            .addValue("storedDocumentId", document.storedDocumentId())
            .addValue("organizationId", document.organizationId())
            .addValue("detectedDocumentType", document.detectedDocumentType())
            .addValue("extractedFields", toJsonString(document.extractedFields()))
            .addValue("rawText", document.rawText())
            .addValue("processedAt", Timestamp.from(document.processedAt()))
            .addValue("reextractionStatus", document.reextractionStatus().name());
    jdbc.update(INSERT_SQL, params);
  }

  @Override
  public void updateExtraction(
      UUID documentId, String detectedDocumentType, Map<String, Object> fields) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("id", documentId)
            .addValue("detectedDocumentType", detectedDocumentType)
            .addValue("extractedFields", toJsonString(fields));
    jdbc.update(UPDATE_EXTRACTION_SQL, params);
  }

  @Override
  public void setReextractionStatus(UUID documentId, ReextractionStatus status) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("id", documentId)
            .addValue("reextractionStatus", status.name());
    jdbc.update(UPDATE_REEXTRACTION_STATUS_SQL, params);
  }

  @Override
  public boolean claimReextractionInProgress(UUID documentId) {
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", documentId);
    return jdbc.update(CLAIM_REEXTRACTION_IN_PROGRESS_SQL, params) == 1;
  }

  private String toJsonString(Map<String, Object> fields) {
    return jsonMapper.writeValueAsString(fields == null ? Map.of() : fields);
  }
}
