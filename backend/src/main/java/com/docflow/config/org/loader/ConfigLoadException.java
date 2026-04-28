package com.docflow.config.org.loader;

public class ConfigLoadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String sourcePath;
  private final int lineNumber;
  private final int columnNumber;

  public ConfigLoadException(String sourcePath, String message, Throwable cause) {
    this(sourcePath, -1, -1, message, cause);
  }

  public ConfigLoadException(
      String sourcePath, int lineNumber, int columnNumber, String message, Throwable cause) {
    super(buildMessage(sourcePath, lineNumber, columnNumber, message), cause);
    this.sourcePath = sourcePath;
    this.lineNumber = lineNumber;
    this.columnNumber = columnNumber;
  }

  public String sourcePath() {
    return sourcePath;
  }

  public int lineNumber() {
    return lineNumber;
  }

  public int columnNumber() {
    return columnNumber;
  }

  private static String buildMessage(String sourcePath, int line, int column, String message) {
    StringBuilder sb = new StringBuilder();
    if (sourcePath != null) {
      sb.append(sourcePath);
      if (line > 0) {
        sb.append(':').append(line);
        if (column > 0) {
          sb.append(':').append(column);
        }
      }
      sb.append(": ");
    }
    sb.append(message);
    return sb.toString();
  }
}
