package com.docflow.document.internal;

import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.ReextractionStatus;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JdbcDocumentReader implements DocumentReader {

  private static final String SELECT_SQL =
      "SELECT id, stored_document_id, organization_id, detected_document_type, "
          + "extracted_fields, raw_text, processed_at, reextraction_status "
          + "FROM documents WHERE id = :id";

  private static final TypeReference<Map<String, Object>> FIELD_MAP_TYPE = new TypeReference<>() {};

  private final NamedParameterJdbcOperations jdbc;
  private final ObjectMapper jsonMapper;

  public JdbcDocumentReader(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
    this.jsonMapper = JsonMapper.builder().build();
  }

  @Override
  public Optional<Document> get(UUID documentId) {
    try {
      Document document = jdbc.queryForObject(SELECT_SQL, Map.of("id", documentId), rowMapper());
      return Optional.ofNullable(document);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private RowMapper<Document> rowMapper() {
    return (ResultSet rs, int rowNum) -> {
      Object extractedFieldsRaw = rs.getObject("extracted_fields");
      Map<String, Object> fields =
          extractedFieldsRaw == null
              ? Map.of()
              : jsonMapper.readValue(extractedFieldsRaw.toString(), FIELD_MAP_TYPE);
      Timestamp processedAt = rs.getTimestamp("processed_at");
      Instant processedAtInstant = processedAt == null ? null : processedAt.toInstant();
      return new Document(
          (UUID) rs.getObject("id"),
          (UUID) rs.getObject("stored_document_id"),
          rs.getString("organization_id"),
          rs.getString("detected_document_type"),
          fields,
          rs.getString("raw_text"),
          processedAtInstant,
          parseStatus(rs));
    };
  }

  private static ReextractionStatus parseStatus(ResultSet rs) throws SQLException {
    String value = rs.getString("reextraction_status");
    return value == null ? ReextractionStatus.NONE : ReextractionStatus.valueOf(value);
  }
}
