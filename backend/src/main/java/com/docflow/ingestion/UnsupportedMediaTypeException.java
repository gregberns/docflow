package com.docflow.ingestion;

public final class UnsupportedMediaTypeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String detectedMimeType;

  public UnsupportedMediaTypeException(String detectedMimeType) {
    super("Unsupported media type: " + detectedMimeType);
    this.detectedMimeType = detectedMimeType;
  }

  public String detectedMimeType() {
    return detectedMimeType;
  }
}
