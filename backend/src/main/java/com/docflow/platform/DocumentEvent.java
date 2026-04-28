package com.docflow.platform;

import java.time.Instant;
import java.util.UUID;

public interface DocumentEvent {

  UUID organizationId();

  Instant occurredAt();
}
