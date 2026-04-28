package com.docflow.config.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "transitions")
public class TransitionEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private String organizationId;

  @Column(name = "document_type_id", nullable = false)
  private String documentTypeId;

  @Column(name = "from_stage", nullable = false)
  private String fromStage;

  @Column(name = "to_stage", nullable = false)
  private String toStage;

  @Column(name = "action", nullable = false)
  private String action;

  @Column(name = "guard_field")
  private String guardField;

  @Column(name = "guard_op")
  private String guardOp;

  @Column(name = "guard_value")
  private String guardValue;

  @Column(name = "ordinal", nullable = false)
  private int ordinal;

  protected TransitionEntity() {}

  public TransitionEntity(UUID id, TransitionKey key, TransitionGuard guard, int ordinal) {
    this.id = id;
    this.organizationId = key.organizationId();
    this.documentTypeId = key.documentTypeId();
    this.fromStage = key.fromStage();
    this.toStage = key.toStage();
    this.action = key.action();
    this.guardField = guard == null ? null : guard.field();
    this.guardOp = guard == null ? null : guard.op();
    this.guardValue = guard == null ? null : guard.value();
    this.ordinal = ordinal;
  }

  public UUID getId() {
    return id;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public String getDocumentTypeId() {
    return documentTypeId;
  }

  public String getFromStage() {
    return fromStage;
  }

  public String getToStage() {
    return toStage;
  }

  public String getAction() {
    return action;
  }

  public String getGuardField() {
    return guardField;
  }

  public String getGuardOp() {
    return guardOp;
  }

  public String getGuardValue() {
    return guardValue;
  }

  public int getOrdinal() {
    return ordinal;
  }

  public record TransitionKey(
      String organizationId,
      String documentTypeId,
      String fromStage,
      String toStage,
      String action) {}

  public record TransitionGuard(String field, String op, String value) {}
}
