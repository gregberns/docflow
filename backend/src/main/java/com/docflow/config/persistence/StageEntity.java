package com.docflow.config.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "stages")
@IdClass(StageId.class)
public class StageEntity {

  @Id
  @Column(name = "organization_id", nullable = false)
  private String organizationId;

  @Id
  @Column(name = "document_type_id", nullable = false)
  private String documentTypeId;

  @Id
  @Column(name = "id", nullable = false)
  private String id;

  @Column(name = "display_name")
  private String displayName;

  @Column(name = "kind", nullable = false)
  private String kind;

  @Column(name = "canonical_status", nullable = false)
  private String canonicalStatus;

  @Column(name = "role")
  private String role;

  @Column(name = "ordinal", nullable = false)
  private int ordinal;

  protected StageEntity() {}

  public StageEntity(
      String organizationId,
      String documentTypeId,
      String id,
      String displayName,
      String kind,
      String canonicalStatus,
      String role,
      int ordinal) {
    this.organizationId = organizationId;
    this.documentTypeId = documentTypeId;
    this.id = id;
    this.displayName = displayName;
    this.kind = kind;
    this.canonicalStatus = canonicalStatus;
    this.role = role;
    this.ordinal = ordinal;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public String getDocumentTypeId() {
    return documentTypeId;
  }

  public String getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getKind() {
    return kind;
  }

  public String getCanonicalStatus() {
    return canonicalStatus;
  }

  public String getRole() {
    return role;
  }

  public int getOrdinal() {
    return ordinal;
  }
}
