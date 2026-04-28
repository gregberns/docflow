package com.docflow.config.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Objects;

public record StageGuardConfig(
    @NotBlank String field, @NotNull GuardOp op, @NotBlank String value) {

  /**
   * Per c1-config-spec.md §7: a missing field reads as {@code null}; equality is computed against
   * the field's string representation. {@code Objects.equals} is used so {@code null}/{@code null}
   * is equal under EQ and unequal under NEQ. The literal {@code value} is non-null by the
   * {@code @NotBlank} constraint, so EQ on a missing key returns false and NEQ returns true.
   */
  public boolean evaluate(Map<String, Object> fields) {
    Object actual = fields == null ? null : fields.get(field);
    String stringified = actual == null ? null : actual.toString();
    return switch (op) {
      case EQ -> Objects.equals(stringified, value);
      case NEQ -> !Objects.equals(stringified, value);
    };
  }
}
