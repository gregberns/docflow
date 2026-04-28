package com.docflow.api.dto;

import java.util.List;

public record DashboardResponse(
    List<Object> processing, List<Object> documents, DashboardStats stats) {}
