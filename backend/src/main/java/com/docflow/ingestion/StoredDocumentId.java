package com.docflow.ingestion;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.Objects;
import java.util.UUID;

public record StoredDocumentId(UUID value) {

  public StoredDocumentId {
    Objects.requireNonNull(value, "value");
  }

  public static StoredDocumentId generate() {
    return new StoredDocumentId(UuidCreator.getTimeOrderedEpoch());
  }

  public static StoredDocumentId of(UUID value) {
    return new StoredDocumentId(value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
