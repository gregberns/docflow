package com.docflow.config.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "document_types")
@IdClass(DocumentTypeId.class)
public class DocumentTypeEntity {

  @Id
  @Column(name = "organization_id", nullable = false)
  private String organizationId;

  @Id
  @Column(name = "id", nullable = false)
  private String id;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "field_schema", nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> fieldSchema;

  protected DocumentTypeEntity() {}

  public DocumentTypeEntity(
      String organizationId, String id, String displayName, Map<String, Object> fieldSchema) {
    this.organizationId = organizationId;
    this.id = id;
    this.displayName = displayName;
    this.fieldSchema = fieldSchema;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public String getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public Map<String, Object> getFieldSchema() {
    return fieldSchema;
  }
}
