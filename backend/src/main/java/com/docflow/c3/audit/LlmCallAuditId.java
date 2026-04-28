package com.docflow.c3.audit;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.Objects;
import java.util.UUID;

public record LlmCallAuditId(UUID value) {

  public LlmCallAuditId {
    Objects.requireNonNull(value, "value");
  }

  public static LlmCallAuditId generate() {
    return new LlmCallAuditId(UuidCreator.getTimeOrderedEpoch());
  }

  public static LlmCallAuditId of(UUID value) {
    return new LlmCallAuditId(value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
