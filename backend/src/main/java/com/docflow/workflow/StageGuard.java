package com.docflow.workflow;

import java.util.Map;
import java.util.Objects;

public sealed interface StageGuard
    permits StageGuard.FieldEquals, StageGuard.FieldNotEquals, StageGuard.Always {

  boolean evaluate(Map<String, Object> fields);

  record FieldEquals(String path, Object value) implements StageGuard {
    @Override
    public boolean evaluate(Map<String, Object> fields) {
      return Objects.equals(fields.get(path), value);
    }
  }

  record FieldNotEquals(String path, Object value) implements StageGuard {
    @Override
    public boolean evaluate(Map<String, Object> fields) {
      return !Objects.equals(fields.get(path), value);
    }
  }

  record Always() implements StageGuard {
    @Override
    public boolean evaluate(Map<String, Object> fields) {
      return true;
    }
  }
}
