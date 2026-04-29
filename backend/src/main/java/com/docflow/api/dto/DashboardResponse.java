package com.docflow.api.dto;

import java.util.List;

public record DashboardResponse(
    List<ProcessingItem> processing, List<DocumentView> documents, DashboardStats stats) {}
