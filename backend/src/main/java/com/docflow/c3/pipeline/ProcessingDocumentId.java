package com.docflow.c3.pipeline;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.Objects;
import java.util.UUID;

public record ProcessingDocumentId(UUID value) {

  public ProcessingDocumentId {
    Objects.requireNonNull(value, "value");
  }

  public static ProcessingDocumentId generate() {
    return new ProcessingDocumentId(UuidCreator.getTimeOrderedEpoch());
  }

  public static ProcessingDocumentId of(UUID value) {
    return new ProcessingDocumentId(value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
