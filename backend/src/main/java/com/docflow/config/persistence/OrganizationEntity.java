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

  protected OrganizationEntity() {}

  public OrganizationEntity(String id, String displayName, String iconId) {
    this.id = id;
    this.displayName = displayName;
    this.iconId = iconId;
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
}
