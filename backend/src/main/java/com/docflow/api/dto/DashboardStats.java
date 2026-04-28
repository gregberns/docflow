package com.docflow.api.dto;

public record DashboardStats(
    long inProgress, long awaitingReview, long flagged, long filedThisMonth) {}
