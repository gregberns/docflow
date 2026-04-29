package com.docflow.config.catalog;

import java.util.List;

public record FieldView(
    String name,
    String type,
    boolean required,
    List<String> enumValues,
    List<FieldView> itemFields) {

  public FieldView {
    enumValues = enumValues == null ? null : List.copyOf(enumValues);
    itemFields = itemFields == null ? null : List.copyOf(itemFields);
  }
}
