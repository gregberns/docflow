package com.docflow.config.catalog;

public record TransitionView(String fromStage, String toStage, String action, GuardView guard) {}
