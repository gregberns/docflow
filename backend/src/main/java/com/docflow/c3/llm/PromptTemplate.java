package com.docflow.c3.llm;

import java.util.Map;
import java.util.Objects;

public final class PromptTemplate {

  private final String raw;

  public PromptTemplate(String raw) {
    this.raw = Objects.requireNonNull(raw, "raw");
  }

  public String raw() {
    return raw;
  }

  public String render(Map<String, String> substitutions) {
    Objects.requireNonNull(substitutions, "substitutions");
    String result = raw;
    for (Map.Entry<String, String> entry : substitutions.entrySet()) {
      String key = Objects.requireNonNull(entry.getKey(), "substitution key");
      String value = Objects.requireNonNull(entry.getValue(), "substitution value");
      result = result.replace("{{" + key + "}}", value);
    }
    return result;
  }
}
