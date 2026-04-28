package com.docflow.config.org.validation;

import java.util.List;

public class ConfigValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final List<String> errors;

  public ConfigValidationException(List<String> errors) {
    super(joinErrors(errors));
    this.errors = List.copyOf(errors);
  }

  public List<String> errors() {
    return errors;
  }

  private static String joinErrors(List<String> errors) {
    if (errors == null || errors.isEmpty()) {
      return "configuration validation failed";
    }
    return "configuration validation failed: " + String.join("; ", errors);
  }
}
