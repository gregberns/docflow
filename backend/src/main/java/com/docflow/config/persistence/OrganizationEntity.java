package com.docflow.config.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizations")
public class OrganizationEntity {

  @Id
  @Column(name = "id", nullable = false)
  private String id;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(name = "icon_id", nullable = false)
  private String iconId;

  @Column(name = "ordinal", nullable = false)
  private int ordinal;

  protected OrganizationEntity() {}

  public OrganizationEntity(String id, String displayName, String iconId, int ordinal) {
    this.id = id;
    this.displayName = displayName;
    this.iconId = iconId;
    this.ordinal = ordinal;
  }

  public String getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getIconId() {
    return iconId;
  }

  public int getOrdinal() {
    return ordinal;
  }
}
