package com.docflow.config.persistence;

import java.io.Serializable;
import java.util.Objects;

public class DocumentTypeId implements Serializable {

  private static final long serialVersionUID = 1L;

  private String organizationId;
  private String id;

  public DocumentTypeId() {}

  public DocumentTypeId(String organizationId, String id) {
    this.organizationId = organizationId;
    this.id = id;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public void setOrganizationId(String organizationId) {
    this.organizationId = organizationId;
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
    if (!(o instanceof DocumentTypeId other)) {
      return false;
    }
    return Objects.equals(organizationId, other.organizationId) && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(organizationId, id);
  }
}
