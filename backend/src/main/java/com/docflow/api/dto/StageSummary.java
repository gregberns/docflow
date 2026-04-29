package com.docflow.api.dto;

public record StageSummary(
    String id, String displayName, String kind, String canonicalStatus, String role) {}
