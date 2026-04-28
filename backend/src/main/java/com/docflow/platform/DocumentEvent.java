package com.docflow.platform;

import java.time.Instant;

public interface DocumentEvent {

  String organizationId();

  Instant occurredAt();
}
