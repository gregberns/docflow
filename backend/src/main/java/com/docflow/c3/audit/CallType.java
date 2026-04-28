package com.docflow.c3.audit;

public enum CallType {
  CLASSIFY("classify"),
  EXTRACT("extract");

  private final String dbValue;

  CallType(String dbValue) {
    this.dbValue = dbValue;
  }

  public String dbValue() {
    return dbValue;
  }

  public static CallType fromDbValue(String value) {
    for (CallType t : values()) {
      if (t.dbValue.equals(value)) {
        return t;
      }
    }
    throw new IllegalArgumentException("unknown call_type: " + value);
  }
}
