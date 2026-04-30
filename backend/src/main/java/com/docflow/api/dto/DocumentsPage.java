package com.docflow.api.dto;

import java.util.List;

public record DocumentsPage(List<DocumentView> items, DocumentCursor nextCursor) {}
