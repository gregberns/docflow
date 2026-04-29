package com.docflow.api.dto;

public record TransitionSummary(
    String fromStage, String toStage, String action, GuardSummary guard) {}
