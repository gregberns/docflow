package com.docflow.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import jakarta.validation.constraints.NotBlank;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "action")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ActionRequest.Approve.class, name = "Approve"),
  @JsonSubTypes.Type(value = ActionRequest.Reject.class, name = "Reject"),
  @JsonSubTypes.Type(value = ActionRequest.Flag.class, name = "Flag"),
  @JsonSubTypes.Type(value = ActionRequest.Resolve.class, name = "Resolve")
})
public sealed interface ActionRequest
    permits ActionRequest.Approve, ActionRequest.Reject, ActionRequest.Flag, ActionRequest.Resolve {

  @JsonTypeName("Approve")
  record Approve() implements ActionRequest {}

  @JsonTypeName("Reject")
  record Reject() implements ActionRequest {}

  @JsonTypeName("Flag")
  record Flag(@NotBlank String comment) implements ActionRequest {}

  @JsonTypeName("Resolve")
  record Resolve() implements ActionRequest {}
}
