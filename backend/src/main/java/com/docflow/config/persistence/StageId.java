package com.docflow.config.persistence;

import java.io.Serializable;
import java.util.Objects;

public class StageId implements Serializable {

  private static final long serialVersionUID = 1L;

  private String organizationId;
  private String documentTypeId;
  private String id;

  public StageId() {}

  public StageId(String organizationId, String documentTypeId, String id) {
    this.organizationId = organizationId;
    this.documentTypeId = documentTypeId;
    this.id = id;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public void setOrganizationId(String organizationId) {
    this.organizationId = organizationId;
  }

  public String getDocumentTypeId() {
    return documentTypeId;
  }

  public void setDocumentTypeId(String documentTypeId) {
    this.documentTypeId = documentTypeId;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StageId other)) {
      return false;
    }
    return Objects.equals(organizationId, other.organizationId)
        && Objects.equals(documentTypeId, other.documentTypeId)
        && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(organizationId, documentTypeId, id);
  }
}
