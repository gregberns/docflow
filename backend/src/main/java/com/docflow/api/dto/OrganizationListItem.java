package com.docflow.api.dto;

import java.util.List;

public record OrganizationListItem(
    String id,
    String name,
    String icon,
    List<String> docTypes,
    long inProgressCount,
    long filedCount) {}
