package com.docflow.api.dto;

import java.time.Instant;
import java.util.UUID;

public record DocumentCursor(Instant updatedAt, UUID id) {}
